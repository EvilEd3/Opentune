package com.opentune.data.innertube

import android.os.SystemClock
import com.opentune.data.Http
import com.opentune.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Turns a videoId into something a video player can show: a video-only
 * stream and the audio stream to go with it (played together, merged), or an
 * HLS manifest that carries both.
 *
 * Three sources, tried in order, each a fallback for the one before:
 *
 *  1. **InnerTubeX** ([InnerTubeXResolver.extractVideo]) — the engine the
 *     audio app leans on: its own client catalog, cipher handling and
 *     BotGuard PoTokens, without which googlevideo serves a URL's first few
 *     ranges and then refuses the rest.
 *  2. **Our own player-client walk** — the clients [StreamResolver] uses, each
 *     URL proven with a range as large as playback will ask for.
 *  3. **NewPipe's extractor.**
 *
 * Every [Source] names the [Source.engine] that made it. When one fails in
 * play, [failed] rules that engine out for the video, and the next [resolve]
 * moves on to the next source instead of handing back the same refused URLs.
 */
object VideoStreams {

    private const val TAG = "OpenTuneVideo"

    /** What to play. Either [videoUrl] + [audioUrl] (merged), or [hlsUrl] alone. */
    class Source(
        val videoUrl: String?,
        val audioUrl: String?,
        val hlsUrl: String?,
        /** Lines of the picture, 0 when unknown (HLS adapts on its own). */
        val height: Int,
        /** "1080p · H.264", for the player's info line. */
        val label: String,
        /** Headers each media request must carry for these URLs. */
        val headers: Map<String, String>,
        /** The largest byte range one request may ask for; these URLs refuse more. */
        val chunkBytes: Long,
        /** File sizes by URL, where known, for reading in ranges. */
        val lengths: Map<String, Long>,
        /** Who made it: "itx:<profile>", "client:<name>" or "newpipe". */
        val engine: String,
    ) {
        val isHls: Boolean get() = videoUrl == null && hlsUrl != null
    }

    /**
     * What the screen can take.
     *
     * [maxHeight] is the ceiling the viewer picked. [allowVp9] lets our own
     * client walk use VP9 (needed above 1080p).
     */
    data class Request(val maxHeight: Int = 1080, val allowVp9: Boolean = false)

    private class Format(
        val url: String?,
        val cipher: String?,
        val mimeType: String,
        val height: Int,
        val fps: Int,
        val bitrate: Long,
        val contentLength: Long?,
    ) {
        private val mime = mimeType.lowercase(Locale.ROOT)
        val isAvc: Boolean get() = "avc1" in mime
        val isVp9: Boolean get() = "vp9" in mime || "vp09" in mime
        val isAacAudio: Boolean get() = mime.startsWith("audio/mp4")
    }

    private class Cached(val source: Source, val at: Long)

    /** Stream URLs live about six hours; an hour is well inside that. */
    private const val CACHE_MS = 60L * 60 * 1000

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Engines that failed in play, by videoId. */
    private val ruledOut = ConcurrentHashMap<String, MutableSet<String>>()

    /** Why each source said no on the last resolve, for the error shown on screen. */
    private val lastReasons = ConcurrentHashMap<String, String>()

    suspend fun resolve(videoId: String, request: Request = Request()): Source = withContext(Dispatchers.IO) {
        val key = "$videoId@${request.maxHeight}/${request.allowVp9}"
        cache[key]?.takeIf { SystemClock.elapsedRealtime() - it.at < CACHE_MS }?.let { return@withContext it.source }

        val skip = ruledOut[videoId].orEmpty().toSet()
        val reasons = mutableListOf<String>()
        val source = attempt("InnerTubeX", reasons) { fromInnerTubeX(videoId, request, skip) }
            ?: attempt("clients", reasons) { fromPlayerClients(videoId, request, skip, reasons) }
            ?: if ("newpipe" in skip) null else attempt("NewPipe", reasons) { fromNewPipe(videoId, request) }
        if (source == null) {
            val why = reasons.joinToString("; ").ifBlank { "no source" }
            lastReasons[videoId] = why
            throw IOException("No playable stream ($why)")
        }
        TrackLog.d(TAG, "video $videoId via ${source.engine}: ${source.label}")
        cache[key] = Cached(source, SystemClock.elapsedRealtime())
        source
    }

    /**
     * [source] failed in play: forget it, and rule its engine out for
     * [videoId], so the next [resolve] tries something else.
     *
     * @return whether anything is left to try.
     */
    fun failed(videoId: String, source: Source): Boolean {
        cache.keys.removeAll { it.startsWith("$videoId@") }
        val set = ruledOut.getOrPut(videoId) { ConcurrentHashMap.newKeySet() }
        set.add(source.engine)
        if (source.engine.startsWith("itx:")) {
            InnerTubeXResolver.exclude(videoId, source.engine.removePrefix("itx:"))
        }
        return set.size < MAX_ENGINES_PER_VIDEO
    }

    /** Gives every source another chance at [videoId], keeping URLs that haven't failed. */
    fun retryAll(videoId: String) {
        ruledOut.remove(videoId)
    }

    private const val MAX_ENGINES_PER_VIDEO = 6

    private suspend fun attempt(name: String, reasons: MutableList<String>, block: suspend () -> Source?): Source? =
        try {
            block().also { if (it == null && name != "clients") reasons += "$name: none" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "$name gave no video: ${e.message}")
            reasons += "$name: ${e.message?.take(60) ?: e.javaClass.simpleName}"
            null
        }

    // ---- 1. InnerTubeX ---------------------------------------------------------

    private suspend fun fromInnerTubeX(videoId: String, request: Request, skip: Set<String>): Source? {
        val profiles = skip.filter { it.startsWith("itx:") }.map { it.removePrefix("itx:") }.toSet()
        // A few profiles at most; each is a full extraction.
        if (profiles.size >= 3) return null
        val v = InnerTubeXResolver.extractVideo(videoId, request.maxHeight, profiles) ?: return null
        val codec = v.videoCodecs.orEmpty().lowercase(Locale.ROOT).let {
            when {
                "avc1" in it -> "H.264"
                "vp9" in it || "vp09" in it -> "VP9"
                "av01" in it -> "AV1"
                else -> null
            }
        }
        val label = when {
            v.isHls -> "Auto · HLS"
            v.height > 0 -> listOfNotNull("${v.height}p", codec).joinToString(" · ")
            else -> "Video"
        }
        val lengths = buildMap {
            v.videoLength?.let { put(v.videoUrl, it) }
            val a = v.audioUrl
            if (a != null) v.audioLength?.let { put(a, it) }
        }
        return Source(
            videoUrl = if (v.isHls) null else v.videoUrl,
            audioUrl = v.audioUrl,
            hlsUrl = if (v.isHls) v.videoUrl else null,
            height = v.height,
            label = label,
            headers = v.headers,
            chunkBytes = v.rangeChunkBytes,
            lengths = lengths,
            engine = "itx:${v.profileId}",
        )
    }

    // ---- 2. Our own player clients --------------------------------------------

    /** The player clients worth asking, plain-URL ones first. */
    private val CLIENTS = listOf(
        PlayerClient.ANDROID_VR,
        PlayerClient.TVHTML5,
        PlayerClient.IOS,
        PlayerClient.ANDROID_MUSIC,
        PlayerClient.ANDROID_VR_LEGACY,
        PlayerClient.IOS_RECENT,
        PlayerClient.ANDROID,
    )

    /**
     * How much one request may ask for, per client. Android VR and iOS URLs
     * serve small ranges and refuse large ones; asking for more than this is
     * what got a stream refused a few seconds in.
     */
    private fun chunkFor(client: PlayerClient): Long = when (client.clientName) {
        "ANDROID_VR" -> 512L * 1024
        "IOS" -> 1024L * 1024
        else -> 2L * 1024 * 1024
    }

    private suspend fun fromPlayerClients(
        videoId: String,
        request: Request,
        skip: Set<String>,
        reasons: MutableList<String>,
    ): Source? {
        runCatching { Innertube.ensureVisitorData() }
        var hlsFallback: Source? = null
        for (client in CLIENTS) {
            val engine = "client:${client.clientName}/${client.clientVersion}"
            if (engine in skip) continue
            try {
                val timestamp = if (client.needsSignatureTimestamp) StreamResolver.signatureTimestamp(videoId) else null
                val response = try {
                    Innertube.player(videoId, client, timestamp)
                } catch (e: Innertube.UnplayableException) {
                    if (e.isAgeGate && Innertube.cookie != null) {
                        Innertube.player(videoId, client, timestamp, authenticated = true)
                    } else {
                        throw e
                    }
                }
                val streaming = response["streamingData"]?.jsonObject
                if (streaming == null) {
                    reasons += "${client.clientName}: no streams"
                    continue
                }
                val chunk = chunkFor(client)
                val formats = adaptive(response)
                val videos = rankVideo(formats.filter { it.mimeType.startsWith("video/") }, request)
                val audio = rankAudio(formats.filter { it.mimeType.startsWith("audio/") })

                val pair = pickPair(videoId, client, videos, audio, chunk)
                if (pair != null) {
                    val v = pair.video
                    return Source(
                        videoUrl = pair.videoUrl,
                        audioUrl = pair.audioUrl,
                        hlsUrl = null,
                        height = v.height,
                        label = "${v.height}p · ${if (v.isAvc) "H.264" else "VP9"}",
                        headers = StreamResolver.mediaHeadersFor(pair.videoUrl),
                        chunkBytes = chunk,
                        lengths = buildMap {
                            v.contentLength?.let { put(pair.videoUrl, it) }
                            pair.audio.contentLength?.let { put(pair.audioUrl, it) }
                        },
                        engine = engine,
                    )
                }
                reasons += "${client.clientName}: refused"
                // iOS answers with an HLS manifest that carries picture and sound together.
                if (hlsFallback == null && "$engine/hls" !in skip) {
                    streaming["hlsManifestUrl"]?.jsonPrimitive?.content?.let { hls ->
                        hlsFallback = Source(
                            null, null, hls, 0, "Auto · HLS", client.mediaHeaders(),
                            chunkBytes = chunk, lengths = emptyMap(), engine = "$engine/hls",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reasons += "${client.clientName}: ${e.message?.take(40) ?: "error"}"
                TrackLog.w(TAG, "${client.clientName} gave no video for $videoId: ${e.message}")
            }
        }
        return hlsFallback
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun adaptive(response: JsonObject): List<Format> =
        response["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
            ?.map { it.jsonObject }
            ?.map {
                Format(
                    url = it.str("url"),
                    cipher = it.str("signatureCipher") ?: it.str("cipher"),
                    mimeType = it.str("mimeType").orEmpty(),
                    height = it.str("height")?.toIntOrNull() ?: 0,
                    fps = it.str("fps")?.toIntOrNull() ?: 0,
                    bitrate = it.str("bitrate")?.toLongOrNull() ?: 0L,
                    contentLength = it.str("contentLength")?.toLongOrNull(),
                )
            }
            ?.filter { it.url != null || it.cipher != null }
            .orEmpty()

    /**
     * Best first: the tallest picture at or under the ceiling, H.264 ahead of
     * VP9 at the same height, then smoother and richer. Anything over the
     * ceiling comes last, smallest first.
     */
    private fun rankVideo(all: List<Format>, request: Request): List<Format> {
        val usable = all.filter { it.isAvc || (request.allowVp9 && it.isVp9) }
            .ifEmpty { all.filter { it.isVp9 } }
        val (fits, over) = usable.partition { it.height <= request.maxHeight }
        return fits.sortedWith(
            compareByDescending<Format> { it.height }
                .thenBy { if (it.isAvc) 0 else 1 }
                .thenByDescending { it.fps }
                .thenByDescending { it.bitrate },
        ) + over.sortedBy { it.height }
    }

    private fun rankAudio(all: List<Format>): List<Format> =
        all.sortedWith(
            compareBy<Format> { if (it.isAacAudio) 0 else 1 }
                .thenByDescending { it.bitrate },
        )

    private class Picked(val video: Format, val videoUrl: String, val audio: Format, val audioUrl: String)

    private suspend fun pickPair(
        videoId: String,
        client: PlayerClient,
        videos: List<Format>,
        audio: List<Format>,
        chunk: Long,
    ): Picked? {
        var audioPick: Pair<Format, String>? = null
        for (a in audio.take(3)) {
            val url = unlock(videoId, client, a.url, a.cipher) ?: continue
            if (probe(url, chunk, "audio/")) { audioPick = a to url; break }
        }
        val (aFormat, aUrl) = audioPick ?: return null
        // A few rungs at most: one refused URL usually means the client is refused.
        for (v in videos.take(3)) {
            val url = unlock(videoId, client, v.url, v.cipher) ?: continue
            if (probe(url, chunk, "video/")) return Picked(v, url, aFormat, aUrl)
        }
        return null
    }

    // ---- 3. NewPipe, the last resort -------------------------------------------

    private suspend fun fromNewPipe(videoId: String, request: Request): Source? = withExtractor {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val videos = extractor.videoOnlyStreams
            .filter { !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter {
                val codec = it.codec.orEmpty().lowercase(Locale.ROOT)
                "avc1" in codec || (request.allowVp9 && ("vp9" in codec || "vp09" in codec))
            }
        val (fits, over) = videos.partition { it.height <= request.maxHeight }
        val video = fits.maxByOrNull { it.height } ?: over.minByOrNull { it.height }
        val audio = extractor.audioStreams
            .filter { !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareBy<AudioStream> {
                    if (it.format?.mimeType?.startsWith("audio/mp4") == true) 0 else 1
                }.thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
        val chunk = 2L * 1024 * 1024
        when {
            video != null && audio != null -> Source(
                videoUrl = video.content,
                audioUrl = audio.content,
                hlsUrl = null,
                height = video.height,
                label = "${video.height}p · ${if ("avc1" in video.codec.orEmpty()) "H.264" else "VP9"}",
                headers = StreamResolver.mediaHeadersFor(video.content),
                chunkBytes = chunk,
                lengths = emptyMap(),
                engine = "newpipe",
            )
            else -> {
                // Muxed, progressive: low resolution, but picture and sound in one file.
                val muxed = extractor.videoStreams
                    .filter { !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxByOrNull { it.height }
                muxed?.let {
                    Source(
                        it.content, null, null, it.height, "${it.height}p",
                        StreamResolver.mediaHeadersFor(it.content),
                        chunkBytes = chunk, lengths = emptyMap(), engine = "newpipe",
                    )
                }
            }
        }
    }

    // ---- Unlocking URLs --------------------------------------------------------

    /**
     * NewPipe's player-JS caches are shared, unsynchronised static state; the
     * audio resolver guards them the same way. One call at a time.
     */
    private val jsLock = Mutex()

    /** One NewPipe extraction at a time: each is a heavy watch-page fetch. */
    private val extractionLock = Mutex()

    /**
     * NewPipe needs a downloader before anything else. The audio resolver
     * installs its own when it first runs; until then, this one.
     */
    private fun ensureNewPipe() {
        if (NewPipe.getDownloader() != null) return
        NewPipe.init(object : Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                val builder = okhttp3.Request.Builder().url(request.url())
                val body = request.dataToSend()
                builder.method(request.httpMethod(), body?.toRequestBody())
                request.headers().forEach { (name, values) ->
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                if (request.headers().keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    builder.header("User-Agent", BROWSER_UA)
                }
                Http.client.newCall(builder.build()).execute().use { r ->
                    return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
                }
            }
        })
    }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** A format's playable URL: signature solved, `n` transformed, client version patched. */
    private suspend fun unlock(videoId: String, client: PlayerClient, url: String?, cipher: String?): String? {
        val raw = url ?: run {
            val params = cipher?.split("&")?.mapNotNull { part ->
                val i = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }?.toMap() ?: return null
            val base = params["url"] ?: return null
            val signature = params["s"] ?: return null
            val solved = runCatching {
                ensureNewPipe()
                jsLock.withLock { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature) }
            }.getOrNull() ?: return null
            "$base${if ("?" in base) "&" else "?"}${params["sp"] ?: "signature"}=$solved"
        }
        val needsN = raw.toHttpUrlOrNull()?.queryParameter("n")?.isNotBlank() == true
        val unlocked = if (!needsN) raw else runCatching {
            ensureNewPipe()
            jsLock.withLock { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, raw) }
        }.getOrDefault(raw)
        return if ("cver=" in unlocked) unlocked.replace(Regex("cver=[^&]+"), "cver=${client.clientVersion}") else unlocked
    }

    private suspend fun <T> withExtractor(block: () -> T): T {
        ensureNewPipe()
        return extractionLock.withLock { withContext(Dispatchers.IO) { block() } }
    }

    // ---- Probe ---------------------------------------------------------------

    private val prober by lazy {
        Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }

    /**
     * Whether [url] serves [type] bytes for a range as large as playback will
     * ask for ([chunk]). A small probe proves nothing: a URL Google has
     * reservations about serves token ranges and refuses real ones.
     */
    private fun probe(url: String, chunk: Long, type: String): Boolean {
        val builder = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-${chunk - 1}")
        StreamResolver.mediaHeadersFor(url).forEach { (k, v) -> builder.header(k, v) }
        return try {
            prober.newCall(builder.build()).execute().use { r ->
                r.isSuccessful && r.header("Content-Type")?.startsWith(type) == true &&
                    r.body?.source()?.request(16L * 1024) == true
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "probe failed: ${e.message}")
            false
        }
    }
}

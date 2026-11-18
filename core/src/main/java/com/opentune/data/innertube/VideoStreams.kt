package com.opentune.data.innertube

import android.os.SystemClock
import com.opentune.data.Http
import com.opentune.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Turns a videoId into something a video player can show: a video-only stream
 * and the audio stream to go with it (played together, merged), or failing
 * that an HLS manifest that carries both.
 *
 * [StreamResolver] is the audio app's resolver and keeps only audio formats;
 * this is its counterpart for picture. It walks the same player clients in the
 * same order and unlocks URLs the same way (NewPipe's signature and `n`
 * solvers), with NewPipe's extractor as the last resort, as there.
 */
object VideoStreams {

    private const val TAG = "OpenTuneVideo"

    /** The player clients worth asking, plain-URL ones first (see [StreamResolver]'s list). */
    private val CLIENTS = listOf(
        PlayerClient.TVHTML5,
        PlayerClient.ANDROID_VR,
        PlayerClient.IOS,
        PlayerClient.ANDROID_MUSIC,
        PlayerClient.ANDROID_VR_LEGACY,
        PlayerClient.IOS_RECENT,
        PlayerClient.ANDROID,
    )

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
    ) {
        val isHls: Boolean get() = videoUrl == null && hlsUrl != null
    }

    /**
     * What the screen can take.
     *
     * [maxHeight] is the ceiling the viewer picked. [allowVp9] lets the higher
     * rungs (1440p, 4K), which YouTube only offers in VP9, be used — worth it
     * only where the TV decodes VP9 in hardware, which the caller checks.
     */
    data class Request(val maxHeight: Int = 1080, val allowVp9: Boolean = false)

    private class Format(
        val url: String?,
        val cipher: String?,
        val mimeType: String,
        val height: Int,
        val fps: Int,
        val bitrate: Long,
    ) {
        private val mime = mimeType.lowercase(Locale.ROOT)
        val isAvc: Boolean get() = "avc1" in mime
        val isVp9: Boolean get() = "vp9" in mime || "vp09" in mime
        val isAacAudio: Boolean get() = mime.startsWith("audio/mp4")
        val isOpusAudio: Boolean get() = "opus" in mime
        val codecLabel: String get() = when {
            isAvc -> "H.264"
            isVp9 -> "VP9"
            else -> mimeType.substringAfter("codecs=\"", "").substringBefore('.').ifBlank { "video" }
        }
    }

    private class Cached(val source: Source, val at: Long)

    /** Stream URLs live about six hours; an hour is well inside that. */
    private const val CACHE_MS = 60L * 60 * 1000

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun resolve(videoId: String, request: Request = Request()): Source = withContext(Dispatchers.IO) {
        val key = "$videoId@${request.maxHeight}/${request.allowVp9}"
        cache[key]?.takeIf { SystemClock.elapsedRealtime() - it.at < CACHE_MS }?.let { return@withContext it.source }

        val source = fromPlayerClients(videoId, request)
            ?: runCatching { fromNewPipe(videoId, request) }
                .onFailure { if (it is CancellationException) throw it; TrackLog.w(TAG, "NewPipe video failed for $videoId: ${it.message}") }
                .getOrNull()
            ?: throw IOException("This video can't be played right now.")
        cache[key] = Cached(source, SystemClock.elapsedRealtime())
        source
    }

    /** Forgets [videoId]'s URLs, after one of them failed in play. */
    fun forget(videoId: String) {
        cache.keys.removeAll { it.startsWith("$videoId@") }
    }

    // ---- Innertube player clients -------------------------------------------

    private suspend fun fromPlayerClients(videoId: String, request: Request): Source? {
        runCatching { Innertube.ensureVisitorData() }
        var hlsFallback: Source? = null
        for (client in CLIENTS) {
            // Clients whose formats need YouTube's player JavaScript solved are
            // tried too, but only after the plain-URL ones, which the order already gives.
            try {
                val response = try {
                    Innertube.player(videoId, client)
                } catch (e: Innertube.UnplayableException) {
                    if (e.isAgeGate && Innertube.cookie != null) Innertube.player(videoId, client, authenticated = true)
                    else throw e
                }
                val streaming = response["streamingData"]?.jsonObject ?: continue
                val formats = adaptive(response)
                val videos = rankVideo(formats.filter { it.mimeType.startsWith("video/") }, request)
                val audio = rankAudio(formats.filter { it.mimeType.startsWith("audio/") }, preferAac = true)

                val pair = pickPair(videoId, client, videos, audio)
                if (pair != null) {
                    val (v, vUrl, aUrl) = pair
                    TrackLog.d(TAG, "video $videoId via ${client.clientName}: ${v.height}p ${v.codecLabel}")
                    return Source(
                        videoUrl = vUrl,
                        audioUrl = aUrl,
                        hlsUrl = null,
                        height = v.height,
                        label = "${v.height}p · ${v.codecLabel}",
                        headers = StreamResolver.mediaHeadersFor(vUrl),
                    )
                }
                // iOS answers with an HLS manifest that carries picture and sound together.
                if (hlsFallback == null) {
                    streaming["hlsManifestUrl"]?.jsonPrimitive?.content?.let { hls ->
                        hlsFallback = Source(null, null, hls, 0, "Auto · HLS", client.mediaHeaders())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
                )
            }
            ?.filter { it.url != null || it.cipher != null }
            .orEmpty()

    /**
     * Best first: the tallest picture at or under the ceiling, H.264 ahead of
     * VP9 at the same height (every TV decodes H.264 in hardware), then
     * smoother and richer. Anything over the ceiling comes last, smallest first.
     */
    private fun rankVideo(all: List<Format>, request: Request): List<Format> {
        val usable = all.filter { it.isAvc || (request.allowVp9 && it.isVp9) }
        val (fits, over) = usable.partition { it.height <= request.maxHeight }
        return fits.sortedWith(
            compareByDescending<Format> { it.height }
                .thenBy { if (it.isAvc) 0 else 1 }
                .thenByDescending { it.fps }
                .thenByDescending { it.bitrate },
        ) + over.sortedBy { it.height }
    }

    private fun rankAudio(all: List<Format>, preferAac: Boolean): List<Format> =
        all.sortedWith(
            compareBy<Format> { if (preferAac && it.isAacAudio) 0 else 1 }
                .thenByDescending { it.bitrate },
        )

    private data class Picked(val video: Format, val videoUrl: String, val audioUrl: String)

    private suspend fun pickPair(videoId: String, client: PlayerClient, videos: List<Format>, audio: List<Format>): Picked? {
        val audioUrl = audio.firstNotNullOfOrNull { unlock(videoId, client, it.url, it.cipher) }
            ?: return null
        // A few rungs at most: one refused URL usually means the client is refused.
        for (v in videos.take(3)) {
            val url = unlock(videoId, client, v.url, v.cipher) ?: continue
            if (probe(url)) return Picked(v, url, audioUrl)
        }
        return null
    }

    // ---- NewPipe, the last resort --------------------------------------------

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
                compareBy<org.schabi.newpipe.extractor.stream.AudioStream> {
                    if (it.format?.mimeType?.startsWith("audio/mp4") == true) 0 else 1
                }.thenByDescending { it.averageBitrate },
            )
            .firstOrNull()
        when {
            video != null && audio != null -> Source(
                videoUrl = video.content,
                audioUrl = audio.content,
                hlsUrl = null,
                height = video.height,
                label = "${video.height}p · ${if ("avc1" in video.codec.orEmpty()) "H.264" else "VP9"}",
                headers = StreamResolver.mediaHeadersFor(video.content),
            )
            else -> {
                // Muxed, progressive: low resolution, but picture and sound in one file.
                val muxed = extractor.videoStreams
                    .filter { !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxByOrNull { it.height }
                muxed?.let {
                    Source(it.content, null, null, it.height, "${it.height}p", StreamResolver.mediaHeadersFor(it.content))
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
        Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** Whether [url] actually serves video bytes, not just a URL that looks right. */
    private fun probe(url: String): Boolean {
        val builder = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-65535")
        StreamResolver.mediaHeadersFor(url).forEach { (k, v) -> builder.header(k, v) }
        return try {
            prober.newCall(builder.build()).execute().use { r ->
                r.isSuccessful && r.header("Content-Type")?.startsWith("video/") == true &&
                    r.body?.source()?.request(4096) == true
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "video probe failed: ${e.message}")
            false
        }
    }
}

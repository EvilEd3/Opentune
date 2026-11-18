package com.opentune.videotv

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.opentune.data.Http
import com.opentune.data.TrackLog
import com.opentune.data.innertube.VideoStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player and its queue of videos.
 *
 * ExoPlayer holds one video at a time; the queue is kept here, because each
 * video's stream has to be resolved before it can be handed over and URLs
 * expire, so resolving the whole queue up front would be wasted. The next
 * video is resolved while the current one plays, so moving on is quick.
 *
 * Nothing but the videos themselves is ever played: no ads are fetched,
 * inserted or shown — the streams come straight from YouTube's player API.
 */
@OptIn(UnstableApi::class)
class VideoSession(context: Context, private val scope: CoroutineScope) {

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * What the on-screen controls drive: ExoPlayer, with next and previous
     * reaching into this queue rather than ExoPlayer's one-item playlist — so
     * the remote's skip buttons and the controls' own work as expected.
     */
    val player: Player = object : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        override fun seekToNext() = next()
        override fun seekToNextMediaItem() = next()
        override fun seekToPrevious() = previous()
        override fun seekToPreviousMediaItem() = previous()
    }

    var queue by mutableStateOf<List<Video>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set

    /** "Loading…", or why a video can't play; null while one is playing. */
    var status by mutableStateOf<String?>(null)
        private set

    /** The stream's quality, "1080p · H.264". */
    var quality by mutableStateOf<String?>(null)
        private set

    val current: Video? get() = queue.getOrNull(index)
    val upNext: Video? get() = queue.getOrNull(index + 1)

    private var loading: Job? = null
    private var extending: Job? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && VideoSettings.autoplay) next()
            }

            override fun onPlayerError(error: PlaybackException) {
                val video = current ?: return
                val failedSource = playing
                val why = describe(error)
                TrackLog.w(TAG, "playback failed for ${video.videoId} on ${failedSource?.engine}: $why")
                // A stream refused part way usually means that source's URLs
                // won't do for this video: rule it out and try the next one,
                // from where it got to.
                val more = failedSource != null && VideoStreams.failed(video.videoId, failedSource)
                if (more && attempts < MAX_ATTEMPTS) {
                    attempts++
                    status = "Stream stopped ($why). Trying another source…"
                    load(resumeAt = exo.currentPosition.coerceAtLeast(0L), keepAttempts = true)
                } else {
                    status = "Couldn't play this video ($why). Skipping…"
                    scope.launch { delay(SKIP_DELAY_MS); if (current == video) next() }
                }
            }
        })
    }

    /** The source now playing, so a failure can be pinned on it. */
    private var playing: VideoStreams.Source? = null
    private var attempts = 0

    /** A short reason for the screen: "HTTP 403", "decoder", "network". */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP ${cause.responseCode}"
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "network"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "this TV can't decode it"
            else -> error.errorCodeName
        }
    }

    /** Plays [videos] from [at], replacing whatever was queued. */
    fun start(videos: List<Video>, at: Int) {
        if (videos.isEmpty()) return
        autoAdded.clear()
        queue = videos.distinctBy { it.videoId }
        index = queue.indexOfFirst { it.videoId == videos[at.coerceIn(videos.indices)].videoId }.coerceAtLeast(0)
        load()
    }

    /** Puts [videos] right after the one playing; starts them if nothing is. */
    fun playNext(videos: List<Video>) {
        if (current == null) return start(videos, 0)
        val ids = videos.map { it.videoId }.toSet()
        // Moved, if already waiting further down: one copy, now next.
        val after = queue.drop(index + 1).filterNot { it.videoId in ids }
        queue = queue.take(index + 1) + videos.filterNot { it.videoId == current?.videoId } + after
        prefetchNext()
    }

    /**
     * Adds [videos] after the last one queued by hand — ahead of the related
     * videos autoplay found, which wait at the end — and starts them if
     * nothing is playing.
     */
    fun addToQueue(videos: List<Video>) {
        if (current == null) return start(videos, 0)
        // Already waiting to play? Then it stays where it is.
        val waiting = queue.drop(index + 1).map { it.videoId }.toSet()
        val fresh = videos.filterNot { it.videoId in waiting || it.videoId == current?.videoId }
        if (fresh.isEmpty()) return
        val firstFound = queue.indexOfFirst { it.videoId in autoAdded }.takeIf { it > index } ?: queue.size
        queue = queue.take(firstFound) + fresh + queue.drop(firstFound)
        if (index + 1 == firstFound) prefetchNext()
    }

    /** Plays [video] now, the queue after it kept. */
    fun playNow(video: Video) {
        if (current == null) return start(listOf(video), 0)
        if (current?.videoId == video.videoId) return
        playNext(listOf(video))
        next()
    }

    /** Empties what's after the one playing. */
    fun clearUpcoming() {
        queue = queue.take(index + 1)
    }

    /** Moves the video at [i] one place up ([by] -1) or down (+1) among those still to play. */
    fun move(i: Int, by: Int) {
        val to = i + by
        if (i <= index || to <= index || i !in queue.indices || to !in queue.indices) return
        queue = queue.toMutableList().also { val v = it.removeAt(i); it.add(to, v) }
        if (to == index + 1 || i == index + 1) prefetchNext()
    }

    /** Videos liked from this TV, this session: what the Like buttons show. */
    var liked by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Likes or unlikes [video] in the account; the button flips at once, back if it fails. */
    fun toggleLike(video: Video, onDone: (String) -> Unit = {}) {
        val now = video.videoId !in liked
        liked = if (now) liked + video.videoId else liked - video.videoId
        scope.launch {
            try {
                VideoFeed.like(video.videoId, now)
                onDone(if (now) "Liked: ${video.title}" else "Removed from Liked music")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                liked = if (now) liked - video.videoId else liked + video.videoId
                onDone("Couldn't update Liked music")
            }
        }
    }

    /** Saves [video] to [playlist] in the account. */
    fun save(video: Video, playlist: PlaylistCard, onDone: (String) -> Unit = {}) {
        scope.launch {
            val ok = try { VideoFeed.saveTo(playlist, video.videoId); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            onDone(if (ok) "Saved to ${playlist.title}" else "Couldn't save to ${playlist.title}")
        }
    }

    /** Takes a video out of the queue; the one playing stays. */
    fun removeAt(i: Int) {
        if (i !in queue.indices || i == index) return
        queue = queue.toMutableList().also { it.removeAt(i) }
        if (i < index) index--
    }

    /** Videos autoplay added (related to what was queued), not the viewer. */
    private val autoAdded = mutableSetOf<String>()

    private fun prefetchNext() {
        val next = upNext ?: return
        scope.launch {
            runCatching {
                val playable = if (next.isVideo) next else VideoFeed.videoVersion(next) ?: return@runCatching
                VideoStreams.resolve(playable.videoId, VideoSettings.request())
            }
        }
    }

    fun jumpTo(i: Int) {
        if (i !in queue.indices || i == index) return
        index = i
        load()
    }

    fun next() {
        if (index + 1 < queue.size) {
            index++
            load()
        } else {
            // At the end of the queue: find more first, then move on.
            extend { if (index + 1 < queue.size) { index++; load() } }
        }
    }

    fun previous() {
        if (exo.currentPosition > RESTART_THRESHOLD_MS || index == 0) exo.seekTo(0)
        else { index--; load() }
    }

    fun pause() = exo.pause()

    fun release() {
        loading?.cancel()
        extending?.cancel()
        exo.release()
    }

    private fun load(resumeAt: Long = 0L, keepAttempts: Boolean = false) {
        val video = current ?: return
        val at = index
        if (!keepAttempts) {
            attempts = 0
            // A fresh start on a video: give every source another chance.
            VideoStreams.retryAll(video.videoId)
        }
        loading?.cancel()
        exo.stop()
        playing = null
        if (!keepAttempts) status = "Loading…"
        quality = null
        loading = scope.launch {
            try {
                // A song from a playlist: play its music video, if it has one,
                // and otherwise the song itself (its picture is the cover).
                val playable = if (video.isVideo) video else VideoFeed.videoVersion(video) ?: video
                if (playable !== video && queue.getOrNull(index)?.videoId == video.videoId) {
                    queue = queue.toMutableList().also { it[index] = playable }
                }
                val source = VideoStreams.resolve(playable.videoId, VideoSettings.request())
                playing = source
                exo.setMediaSource(mediaSourceFor(source, video), resumeAt)
                exo.prepare()
                exo.playWhenReady = true
                status = null
                quality = source.label
                // Keep the queue topped up, and the next video ready to go.
                if (VideoSettings.autoplay && index >= queue.size - 2) extend()
                prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "no stream for ${video.videoId}: ${e.message}")
                status = "Couldn't play this video: ${e.message?.take(140) ?: "no stream"}. Skipping…"
                delay(ERROR_SKIP_DELAY_MS)
                if (index == at && index + 1 < queue.size) { index++; load() }
            }
        }
    }

    /** Adds music videos related to the last one in the queue. */
    private fun extend(then: () -> Unit = {}) {
        if (extending?.isActive == true) return
        val seed = queue.lastOrNull() ?: return
        extending = scope.launch {
            val more = runCatching { VideoFeed.upNext(seed, queue.map { it.videoId }.toSet()) }.getOrDefault(emptyList())
            if (more.isNotEmpty()) {
                autoAdded += more.map { it.videoId }
                queue = (queue + more).distinctBy { it.videoId }
            }
            then()
        }
    }

    private fun mediaSourceFor(source: VideoStreams.Source, video: Video): MediaSource {
        val http = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(source.headers)
        val item = MediaItem.Builder()
            .setMediaId(video.videoId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).setArtist(video.artist).build())

        val hls = source.hlsUrl
        if (source.isHls && hls != null) {
            return HlsMediaSource.Factory(http)
                .createMediaSource(item.setUri(hls).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        }
        val chunked = ChunkedDataSource.Factory(http, source.chunkBytes, source.lengths)
        val picture = ProgressiveMediaSource.Factory(chunked)
            .createMediaSource(item.setUri(source.videoUrl.orEmpty()).build())
        val sound = source.audioUrl ?: return picture
        return MergingMediaSource(
            picture,
            ProgressiveMediaSource.Factory(chunked).createMediaSource(MediaItem.fromUri(sound)),
        )
    }

    private companion object {
        const val TAG = "OpenTuneVideo"
        const val MAX_ATTEMPTS = 4
        const val SKIP_DELAY_MS = 3000L
        /** Long enough to read the reason off the screen. */
        const val ERROR_SKIP_DELAY_MS = 6000L
        const val RESTART_THRESHOLD_MS = 5000L
    }
}

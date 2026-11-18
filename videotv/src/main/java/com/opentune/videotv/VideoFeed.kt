package com.opentune.videotv

import com.opentune.data.MusicRepository
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * One music video: everything a card and the player need.
 *
 * [isVideo] is false for a song from a playlist that is the audio-only
 * catalogue track: the player looks up its music video before playing it.
 */
data class Video(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String? = null,
    val isVideo: Boolean = true,
) {
    /**
     * YouTube's own frame for the video, 480×360 with the picture letterboxed
     * in it; cropped to 16:9 on the card, the bars fall away.
     */
    val thumbnail: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

/** A playlist in the signed-in account's library. */
data class PlaylistCard(val browseId: String, val title: String, val subtitle: String, val thumbnail: String?)

data class VideoRow(val title: String, val videos: List<Video>)

/**
 * Where the videos come from. Everything here asks YouTube Music for its
 * *video* uploads only — the official music videos and performances — never
 * the audio-only catalogue tracks, so every card is something to watch.
 */
object VideoFeed {

    /** The genre rows under the charts, each a YouTube Music video search. */
    private val GENRES = listOf(
        "Trending now" to "trending music videos",
        "Pop" to "pop music videos",
        "Hip-hop & rap" to "hip hop music videos",
        "R&B & soul" to "r&b music videos",
        "Rock" to "rock music videos",
        "Dance & electronic" to "edm music videos",
        "Latin" to "latin music videos",
        "K-pop" to "kpop music videos",
        "Bollywood" to "bollywood songs video",
        "Country" to "country music videos",
        "Throwback hits" to "2000s hits music videos",
        "Live performances" to "live performance music",
    )

    suspend fun home(): List<VideoRow> = withContext(Dispatchers.IO) {
        coroutineScope {
            val charts = async { quietly { chartRows() } }
            val fresh = async { quietly { newReleaseRows() } }
            val genres = GENRES.map { (title, query) ->
                async { quietly { listOf(VideoRow(title, search(query))) } }
            }
            (charts.await() + fresh.await() + genres.awaitAll().flatten())
                .filter { it.videos.size >= 3 }
                .distinctBy { it.title }
        }
    }

    /** YouTube Music video search: music videos only. */
    suspend fun search(query: String): List<Video> =
        MusicRepository.search(query, SearchFilter.VIDEOS)
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            // A video search finds videos, however each row is labelled.
            .map { it.toVideo().copy(isVideo = true) }
            .distinctBy { it.videoId }

    suspend fun suggestions(input: String): List<String> =
        runCatching { MusicRepository.suggestions(input) }.getOrDefault(emptyList())

    /**
     * More to watch after [seed]: the music videos in YouTube Music's own radio
     * for it — each song's video version where one exists, audio-only tracks
     * left out — or, when that has none, more videos by the same artist.
     */
    suspend fun upNext(seed: Video, exclude: Set<String>): List<Video> = withContext(Dispatchers.IO) {
        val radio = quietly {
            InnertubeParser.parseWatchQueue(Innertube.next(seed.videoId), allVersions = true)
                .filter { it.isVideo }
                .map { it.toVideo() }
        }
        val fresh = radio.filter { it.videoId !in exclude && it.videoId != seed.videoId }.distinctBy { it.videoId }
        fresh.ifEmpty {
            quietly { search("${seed.artist} music video") }
                .filter { it.videoId !in exclude && it.videoId != seed.videoId }
        }
    }

    // ---- The account's library -------------------------------------------------

    /** The signed-in account's playlists, Liked music first. */
    suspend fun playlists(): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val liked = PlaylistCard(LIKED, "Liked music", "Your likes", null)
        listOf(liked) + MusicRepository.libraryPlaylists()
            .mapNotNull { item ->
                val id = item.browseId ?: return@mapNotNull null
                if (id == "VLLM" || id == LIKED) return@mapNotNull null
                PlaylistCard(id, item.title, item.subtitle, item.thumbnailUrl)
            }
            .distinctBy { it.browseId }
    }

    /** A playlist's tracks, music videos and songs alike, in order. */
    suspend fun playlist(browseId: String): List<Video> = withContext(Dispatchers.IO) {
        val songs = if (browseId == LIKED) {
            MusicRepository.accountLikedSongs()
        } else {
            MusicRepository.remotePlaylist(browseId.removePrefix("VL")).songs
        }
        songs.map { it.toVideo() }.distinctBy { it.videoId }
    }

    private const val LIKED = "LIKED"

    private val versions = java.util.concurrent.ConcurrentHashMap<String, Video>()

    /**
     * The music video for [song], an audio-only track: YouTube Music's own
     * pairing of the two (its Song/Video switch), or failing that the first
     * music video found by title and artist. Null when it has none.
     */
    suspend fun videoVersion(song: Video): Video? = withContext(Dispatchers.IO) {
        if (song.isVideo) return@withContext song
        versions[song.videoId]?.let { return@withContext it }
        val target = normalize(song.title)
        val paired = quietly {
            InnertubeParser.parseWatchQueue(Innertube.next(song.videoId), allVersions = true)
                .take(2)
                .filter { it.isVideo && it.videoId != song.videoId }
                .map { it.toVideo() }
        }.firstOrNull { sameSong(normalize(it.title), target) }
        val found = paired ?: quietly { search("${song.title} ${song.artist}") }
            .firstOrNull { sameSong(normalize(it.title), target) }
        found?.let { video ->
            // The song's own name and credit read better than an upload's title.
            Video(video.videoId, song.title, song.artist, video.duration ?: song.duration)
                .also { versions[song.videoId] = it }
        }
    }

    /** "Song Name (Official Video) [4K]" → "song name". */
    private fun normalize(title: String): String =
        title.lowercase()
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sameSong(candidate: String, target: String): Boolean =
        target.isNotEmpty() && (candidate == target || candidate.startsWith(target) || target in candidate)

    // ---- Home sources ----------------------------------------------------------

    /** The charts page's video shelves ("Top music videos", "Trending"). */
    private suspend fun chartRows(): List<VideoRow> =
        videoShelves(InnertubeParser.parseHome(Innertube.charts(null)))

    /** The new-releases page's "New music videos" shelf. */
    private suspend fun newReleaseRows(): List<VideoRow> =
        videoShelves(MusicRepository.shelves("FEmusic_new_releases", null))

    private fun videoShelves(shelves: List<HomeShelf>): List<VideoRow> =
        shelves
            .filter { it.title.contains("video", ignoreCase = true) || it.title.contains("trending", ignoreCase = true) }
            .map { shelf ->
                VideoRow(
                    shelf.title,
                    shelf.items.mapNotNull { item ->
                        item.videoId?.let { Video(it, item.title, item.subtitle.substringBefore(" • ").trim()) }
                    },
                )
            }

    private fun Song.toVideo() = Video(videoId, title, artist, durationText, isVideo)

    private suspend fun <T> quietly(block: suspend () -> List<T>): List<T> =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
}

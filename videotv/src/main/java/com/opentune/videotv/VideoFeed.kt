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

/** One music video: everything a card and the player need. */
data class Video(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String? = null,
) {
    /**
     * YouTube's own frame for the video, 480×360 with the picture letterboxed
     * in it; cropped to 16:9 on the card, the bars fall away.
     */
    val thumbnail: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

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
            .map { it.toVideo() }
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

    private fun Song.toVideo() = Video(videoId, title, artist, durationText)

    private suspend fun <T> quietly(block: suspend () -> List<T>): List<T> =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
}

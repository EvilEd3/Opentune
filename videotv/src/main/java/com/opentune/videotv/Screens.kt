package com.opentune.videotv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** A page's content as it arrives. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

/** A video's 16:9 frame, with its title and artist under it. */
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 320.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(width)) {
        TvSurface(onClick = onClick, onLongClick = onLongClick, shape = shape, scale = 1.06f, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Cover(video.thumbnail, Modifier.matchParentSize(), shape)
            if (video.duration != null) {
                Text(
                    video.duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.Text,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(video.artist, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

// ---- Home ---------------------------------------------------------------------

/** Rows of music videos: the charts, new videos, then by genre. */
@Composable
fun HomeScreen(
    rows: Load<List<VideoRow>>,
    listState: LazyListState,
    onRetry: () -> Unit,
    pick: (Video) -> Unit,
    quickAdd: (Video) -> Unit,
    openPlaylist: (PlaylistCard) -> Unit,
) {
    when (rows) {
        Load.Loading -> TvNote("Loading music videos…")
        Load.Failed -> Column {
            TvNote("Couldn't load videos. Check the connection.")
            TvButton("Try again", onRetry, icon = Icons.Rounded.Refresh, modifier = Modifier.padding(horizontal = TvSafe.Horizontal))
        }
        is Load.Done -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            itemsIndexed(rows.value, key = { _, row -> row.title }) { _, row ->
                TvShelf(row.title) {
                    itemsIndexed(row.videos, key = { _, v -> v.videoId }) { _, video ->
                        VideoCard(video, onClick = { pick(video) }, onLongClick = { quickAdd(video) })
                    }
                    itemsIndexed(row.playlists, key = { _, p -> p.browseId }) { _, card ->
                        Box(Modifier.width(200.dp)) { PlaylistTile(card, onClick = { openPlaylist(card) }) }
                    }
                }
            }
        }
    }
}

// ---- Search -------------------------------------------------------------------

/** What the search page holds on to while the player is open. */
class SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<Load<List<Video>>>(Load.Done(emptyList()))
    var searched by mutableStateOf("")
    /** What of the account's own library matches, shown first. */
    var library by mutableStateOf(VideoFeed.LibraryHits(emptyList(), emptyList()))
}

/** Search as you type, music videos only. The TV's keyboard opens on the field. */
@Composable
fun SearchScreen(
    state: SearchState,
    pick: (Video) -> Unit,
    quickAdd: (Video) -> Unit,
    openPlaylist: (PlaylistCard) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { if (state.query.isBlank()) runCatching { focus.requestFocus() } }
    LaunchedEffect(state.query) {
        val q = state.query.trim()
        if (q == state.searched) return@LaunchedEffect
        if (q.length < 2) {
            state.results = Load.Done(emptyList())
            state.library = VideoFeed.LibraryHits(emptyList(), emptyList())
            state.searched = q
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        state.results = Load.Loading
        coroutineScope {
            // The library and YouTube Music at once; the library is usually quicker.
            val mine = async { runCatching { VideoFeed.searchLibrary(q) }.getOrNull() }
            state.results = try { Load.Done(VideoFeed.search(q)) } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
            state.library = mine.await() ?: VideoFeed.LibraryHits(emptyList(), emptyList())
        }
        state.searched = q
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal).focusRequester(focus),
            placeholder = { Text("Search music videos") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            textStyle = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        when (val r = state.results) {
            Load.Loading -> TvNote("Searching…")
            Load.Failed -> TvNote("Couldn't search just now. Check the connection.")
            is Load.Done -> {
                if (r.value.isEmpty() && state.library.isEmpty && state.query.trim().length >= 2 && state.searched == state.query.trim()) {
                    TvNote("No music videos found for “${state.query.trim()}”.")
                }
                val mine = state.library
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    if (!mine.isEmpty) {
                        // From the account's own library, ahead of YouTube Music's results.
                        item(span = { GridItemSpan(maxLineSpan) }, key = "library") {
                            Column {
                                Text("From your library", style = MaterialTheme.typography.titleLarge)
                                LazyRow(
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    items(mine.songs, key = { "s" + it.videoId }) { song ->
                                        VideoCard(song, onClick = { pick(song) }, onLongClick = { quickAdd(song) }, width = 240.dp)
                                    }
                                    items(mine.playlists, key = { "p" + it.browseId }) { card ->
                                        Box(Modifier.width(150.dp)) { PlaylistTile(card, onClick = { openPlaylist(card) }) }
                                    }
                                }
                                if (r.value.isNotEmpty()) Text("Music videos", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    itemsIndexed(r.value, key = { _, v -> v.videoId }) { _, video ->
                        VideoCard(
                            video, onClick = { pick(video) }, onLongClick = { quickAdd(video) },
                            width = Dp.Unspecified, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private const val DEBOUNCE_MS = 450L

// ---- Settings -----------------------------------------------------------------

@Composable
fun SettingsScreen(remote: TvRemote?, onPair: () -> Unit, onSignIn: () -> Unit) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column {
                Text("Video quality", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (VideoSettings.hasHardwareVp9) "The highest the video is played at. This TV can play up to 4K."
                    else "The highest the video is played at. This TV plays up to 1080p from YouTube.",
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VideoSettings.choices().forEach { q ->
                        TvPill(q.label, selected = VideoSettings.quality == q, onClick = { VideoSettings.chooseQuality(q) })
                    }
                }
            }
        }
        item {
            Column {
                Text("Size", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How big everything on the screen is. Large is the size the app first had.",
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UiSize.entries.forEach { size ->
                        TvPill(size.label, selected = VideoSettings.size == size, onClick = { VideoSettings.chooseSize(size) })
                    }
                }
            }
        }
        item {
            Column {
                Text("Autoplay", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "When a video ends, play the next one — related music videos once the row runs out.",
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvPill("On", selected = VideoSettings.autoplay, onClick = { VideoSettings.chooseAutoplay(true) })
                    TvPill("Off", selected = !VideoSettings.autoplay, onClick = { VideoSettings.chooseAutoplay(false) })
                }
            }
        }
        item {
            Column {
                Text("YouTube Music account", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (signedIn) {
                        "Signed in${account?.let { " as ${it.name}" }.orEmpty()}. Your playlists are in Library."
                    } else {
                        "Sign in with the OpenTune app on your phone to play your playlists and liked songs."
                    },
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                if (signedIn) TvButton("Sign out", { AccountStore.signOut() })
                else TvButton("Sign in with your phone", onSignIn, primary = true)
            }
        }
        if (remote != null) {
            item { PhoneRemoteSettings(remote, onPair) }
        }
        item {
            Column {
                Text("About", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "OpenTune Videos plays music videos from YouTube Music, with no ads, no account and no tracking. " +
                        "Built on OpenTune, under the GNU GPL v3.",
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
            }
        }
    }
}

package com.opentune.videotv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One choice in an [ActionMenu]. */
class MenuAction(val label: String, val primary: Boolean = false, val run: () -> Unit)

/**
 * A small menu over the page, for what to do with a video: Play now, Play
 * next, Add to queue. Back or Cancel closes it.
 *
 * The menu moves its own focus: Up and Down step through its buttons and
 * every other arrow is swallowed, so nothing behind it can take focus (or
 * scroll) while it is open.
 */
@Composable
fun ActionMenu(title: String, subtitle: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val all = actions + MenuAction("Cancel") {}
    val requesters = remember(all.size) { List(all.size) { FocusRequester() } }
    var at by remember { mutableIntStateOf(0) }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The menu may not be laid out on the first frame; try until focus is in it.
        repeat(15) {
            runCatching { requesters[0].requestFocus() }
            delay(40)
            if (inside) return@LaunchedEffect
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent.keyCode
                val arrow = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                    key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                if (!arrow) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val to = when (key) {
                        KeyEvent.KEYCODE_DPAD_UP -> (at - 1).coerceAtLeast(0)
                        KeyEvent.KEYCODE_DPAD_DOWN -> (at + 1).coerceAtMost(all.lastIndex)
                        else -> at
                    }
                    if (to != at) runCatching { requesters[to].requestFocus() }
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .heightIn(max = 500.dp)
                .onFocusChanged { inside = it.hasFocus }
                .background(TvColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TvColors.TextDim, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            all.forEachIndexed { i, action ->
                TvButton(
                    action.label,
                    onClick = { onDismiss(); action.run() },
                    primary = action.primary,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) at = i },
                    focusRequester = requesters[i],
                )
            }
        }
    }
}

/** Which of the account's playlists to save [video] to. */
@Composable
fun PlaylistPicker(video: Video, library: LibraryState, onPick: (PlaylistCard) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        if (library.playlists !is Load.Done) {
            library.playlists = Load.Loading
            library.playlists = try {
                Load.Done(VideoFeed.playlists())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { Load.Failed }
        }
    }
    when (val p = library.playlists) {
        is Load.Done -> ActionMenu(
            title = "Add to a playlist",
            subtitle = video.title,
            // Liked music isn't a playlist to add to: that's what Like is for.
            actions = p.value.filter { it.browseId != "LIKED" }.map { card -> MenuAction(card.title) { onPick(card) } },
            onDismiss = onDismiss,
        )
        Load.Failed -> ActionMenu("Add to a playlist", "Couldn't load your playlists.", emptyList(), onDismiss)
        else -> ActionMenu("Add to a playlist", "Loading your playlists…", emptyList(), onDismiss)
    }
}

/**
 * The queue: what's playing, what comes after it, and the related videos
 * autoplay found at the end. Pick one to play it now or take it out.
 */
@Composable
fun QueueScreen(session: VideoSession, onOpen: (Int) -> Unit, onClearUpcoming: () -> Unit) {
    val queue = session.queue
    val list = rememberLazyListState(initialFirstVisibleItemIndex = session.index.coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.headlineSmall)
                val upcoming = (queue.size - session.index - 1).coerceAtLeast(0)
                Text(
                    if (queue.isEmpty()) "Nothing queued." else "$upcoming up next",
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
            }
            if (queue.size > session.index + 1) TvButton("Clear up next", onClearUpcoming)
        }
        Spacer(Modifier.height(12.dp))
        if (queue.isEmpty()) {
            TvNote("Pick a video and choose Add to queue, or add a whole playlist from Library.")
            return
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = TvSafe.Horizontal - 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(queue, key = { i, v -> "${v.videoId}#$i" }) { i, video ->
                TrackRow(
                    video,
                    onClick = { onOpen(i) },
                    highlight = i == session.index,
                    note = when {
                        i == session.index -> "Playing"
                        i < session.index -> "Played"
                        else -> null
                    },
                )
            }
        }
    }
}

/**
 * What's playing, in the top right of every page while browsing: its cover,
 * title and artist, and whether it's playing. Light on purpose — no video is
 * drawn here (the sound carries on, the picture waits for full screen), so
 * browsing stays smooth. OK on it, or Back on a main page, returns to the
 * video full screen.
 */
@Composable
fun NowPlayingPanel(session: VideoSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val video = session.current ?: return
    val shape = RoundedCornerShape(14.dp)
    var isPlaying by remember { mutableStateOf(session.exo.isPlaying) }
    DisposableEffect(session) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        session.exo.addListener(listener)
        onDispose { session.exo.removeListener(listener) }
    }
    TvSurface(onClick = onOpen, shape = shape, scale = 1.04f, modifier = modifier) { focused ->
        Row(
            Modifier
                .background(if (focused) TvColors.SurfaceHigh else TvColors.Surface, shape)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 96.dp, height = 54.dp), contentAlignment = Alignment.Center) {
                Cover(video.thumbnail, Modifier.matchParentSize(), RoundedCornerShape(8.dp))
                Box(
                    Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                        null, Modifier.size(18.dp), tint = TvColors.Accent,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (isPlaying) "Now playing" else "Paused",
                    style = MaterialTheme.typography.labelSmall, color = TvColors.Accent,
                )
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    session.status ?: video.artist,
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A line along the bottom that says what just happened ("Added to queue"), briefly. */
@Composable
fun Notice(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Box(modifier.fillMaxWidth().padding(bottom = 40.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(TvColors.SurfaceHigh, RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 14.dp),
        )
    }
}

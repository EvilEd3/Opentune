package com.opentune.videotv

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** One choice in an [ActionMenu]. */
class MenuAction(val label: String, val primary: Boolean = false, val run: () -> Unit)

/**
 * A small menu over the page, for what to do with a video: Play now, Play
 * next, Add to queue. Back or Cancel closes it.
 */
@Composable
fun ActionMenu(title: String, subtitle: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(TvColors.SurfaceHigh, RoundedCornerShape(24.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TvColors.TextDim, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            actions.forEachIndexed { i, action ->
                TvButton(
                    action.label,
                    onClick = { onDismiss(); action.run() },
                    primary = action.primary,
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (i == 0) first else null,
                )
            }
            TvButton("Cancel", onDismiss, modifier = Modifier.fillMaxWidth())
        }
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
 * What's playing, small, in the corner of the top bar while browsing: the
 * video keeps going while you look for the next one. OK opens it full screen.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(session: VideoSession, onOpen: () -> Unit) {
    val video = session.current ?: return
    val shape = RoundedCornerShape(12.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.widthIn(max = 260.dp), horizontalAlignment = Alignment.End) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                session.status ?: video.artist,
                style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TvSurface(onClick = onOpen, shape = shape, scale = 1.08f, modifier = Modifier.size(width = 192.dp, height = 108.dp)) { _ ->
            AndroidView(
                factory = { context ->
                    (LayoutInflater.from(context).inflate(R.layout.mini_player, null, false) as PlayerView).apply {
                        player = session.player
                        isFocusable = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.matchParentSize().clip(shape),
            )
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

package com.opentune.videotv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * The video, full screen. Media3's own controls draw over it and are made for
 * a remote: OK pauses, left and right seek, the skip buttons move through the
 * queue. Down, while the controls are hidden, opens the Up next panel: the
 * queue as a row along the bottom (pick one to play it, hold OK to move or
 * remove it), with Like and Add to a playlist for the video playing. Back
 * closes the panel, then hides the controls, then leaves the player — the
 * video keeps playing beside the pages.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    session: VideoSession,
    onClose: () -> Unit,
    signedIn: Boolean,
    onSave: (Video) -> Unit,
    tell: (String) -> Unit,
) {
    var controlsShown by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    /** The queue item held down in the panel: its menu is open. */
    var holding by remember { mutableStateOf<Int?>(null) }

    BackHandler {
        val v = view
        when {
            panel -> panel = false
            v != null && controlsShown -> v.hideController()
            else -> onClose()
        }
    }

    // Down with the controls hidden opens Up next, before the video view takes it as "show controls".
    val shown by rememberUpdatedState(controlsShown)
    val open by rememberUpdatedState(panel)
    DisposableEffect(Unit) {
        KeyHook.handler = { event ->
            if (!open && !shown && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) panel = true
                true
            } else {
                false
            }
        }
        onDispose { KeyHook.handler = null }
    }

    // Leaving the TV app (Home button, input switch) pauses; nothing plays unseen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // With the panel shut, focus goes back to the video so the remote drives it again.
    LaunchedEffect(panel) {
        val v = view ?: return@LaunchedEffect
        if (panel) {
            v.hideController()
        } else {
            delay(30)
            v.requestFocus()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = session.player
                    useController = true
                    controllerShowTimeoutMs = CONTROLS_TIMEOUT_MS
                    controllerAutoShow = true
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowSubtitleButton(false)
                    setShowShuffleButton(false)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controlsShown = visibility == View.VISIBLE },
                    )
                    view = this
                    post { requestFocus(); showController() }
                }
            },
            update = { v ->
                // While the panel is open the video can't take focus, so Up and Down stay in the panel.
                v.useController = !panel
                v.isFocusable = !panel
                v.descendantFocusability = if (panel) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // What's playing, over the top of the picture while the controls are up.
        AnimatedVisibility(
            visible = (controlsShown || session.status != null) && !panel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical),
            ) {
                val video = session.current
                if (video != null) {
                    Text(video.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(video.artist, session.quality).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium, color = TvColors.TextDim, maxLines = 1,
                    )
                }
                session.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TvColors.Accent) }
                session.upNext?.let { next ->
                    Text(
                        "Up next: ${next.title} — ${next.artist}",
                        style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Back: browse while it plays  ·  ▼ with the controls hidden: Up next",
                    style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim.copy(alpha = 0.7f),
                )
            }
        }

        AnimatedVisibility(
            visible = panel,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            UpNextPanel(
                session = session,
                signedIn = signedIn,
                onSave = onSave,
                tell = tell,
                onHold = { holding = it },
                onClose = { panel = false },
            )
        }

        holding?.let { i ->
            val video = session.queue.getOrNull(i)
            if (video == null) {
                holding = null
            } else {
                ActionMenu(
                    title = video.title,
                    subtitle = video.artist,
                    actions = buildList {
                        add(MenuAction("Play now", primary = true) { session.jumpTo(i); panel = false })
                        if (i > session.index + 1) add(MenuAction("Move earlier") { session.move(i, -1) })
                        if (i < session.queue.lastIndex) add(MenuAction("Move later") { session.move(i, 1) })
                        add(MenuAction("Remove from queue") { session.removeAt(i) })
                    },
                    onDismiss = { holding = null },
                )
            }
        }
    }
}

/**
 * The bottom of the player while Up next is open: buttons for the video
 * playing, then what's queued after it, left to right. Up from the buttons
 * (or Back) closes it.
 */
@Composable
private fun UpNextPanel(
    session: VideoSession,
    signedIn: Boolean,
    onSave: (Video) -> Unit,
    tell: (String) -> Unit,
    onHold: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val firstCard = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    var onButtons by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf(false) }
    val current = session.current
    val upcoming = session.queue.withIndex().drop(session.index + 1)

    LaunchedEffect(Unit) {
        // Into the panel as soon as it is laid out; then leave focus alone.
        repeat(15) {
            runCatching { if (upcoming.isNotEmpty()) firstCard.requestFocus() else firstButton.requestFocus() }
            delay(40)
            if (inside) return@LaunchedEffect
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f), Color.Black)))
            .padding(top = 48.dp, bottom = TvSafe.Vertical)
            .onFocusChanged { inside = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && onButtons) {
                    if (event.type == KeyEventType.KeyDown) onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TvSafe.Horizontal)
                .onFocusChanged { onButtons = it.hasFocus },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (upcoming.isEmpty()) "Nothing queued after this" else "Up next · ${upcoming.size}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (current != null && signedIn) {
                val liked = current.videoId in session.liked
                TvButton(
                    if (liked) "Liked" else "Like",
                    { session.toggleLike(current) { tell(it) } },
                    icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    focusRequester = firstButton,
                )
                TvButton("Add to a playlist", { onSave(current) }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
            } else {
                TvButton("Close", onClose, focusRequester = firstButton)
            }
            if (upcoming.isNotEmpty()) TvButton("Clear up next", { session.clearUpcoming() })
        }
        if (upcoming.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(upcoming, key = { _, item -> "${item.value.videoId}#${item.index}" }) { n, item ->
                    VideoCard(
                        item.value,
                        onClick = { session.jumpTo(item.index); onClose() },
                        onLongClick = { onHold(item.index) },
                        width = 260.dp,
                        modifier = if (n == 0) Modifier.focusRequester(firstCard) else Modifier,
                    )
                }
            }
            Text(
                "OK: play now  ·  hold OK: move or remove",
                style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim,
                modifier = Modifier.padding(horizontal = TvSafe.Horizontal),
            )
        }
    }
}

private const val CONTROLS_TIMEOUT_MS = 4000

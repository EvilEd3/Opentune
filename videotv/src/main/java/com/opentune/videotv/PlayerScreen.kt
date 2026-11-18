package com.opentune.videotv

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * The video, full screen. Media3's own controls draw over it and are made for
 * a remote: any D-pad press shows them, left and right seek, OK pauses, and
 * the skip buttons move through the queue. Back hides the controls first,
 * then leaves the player.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(session: VideoSession, onClose: () -> Unit) {
    var controlsShown by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<PlayerView?>(null) }

    BackHandler {
        val v = view
        if (v != null && controlsShown) v.hideController() else onClose()
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
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // What's playing, over the top of the picture while the controls are up.
        AnimatedVisibility(
            visible = controlsShown || session.status != null,
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
            }
        }
    }
}

private const val CONTROLS_TIMEOUT_MS = 4000

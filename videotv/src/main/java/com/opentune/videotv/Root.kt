package com.opentune.videotv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Tab(val label: String) { Home("Home"), Search("Search"), Settings("Settings") }

/**
 * The whole app: a bar of tabs over Home, Search and Settings, and the player
 * full screen over all of it once a video is picked. What each page has
 * loaded is held here, so coming back from a video finds it as it was.
 */
@Composable
fun Root(voice: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { VideoSession(context.applicationContext, scope) }
    DisposableEffect(session) { onDispose { session.release() } }

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var playing by remember { mutableStateOf(false) }

    // Home's rows, loaded once and kept.
    var rows by remember { mutableStateOf<Load<List<VideoRow>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val homeList = rememberLazyListState()
    LaunchedEffect(reload) {
        rows = Load.Loading
        rows = try {
            VideoFeed.home().let { if (it.isEmpty()) Load.Failed else Load.Done(it) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }

    val search = remember { SearchState() }

    // "Play … on OpenTune Videos" from the TV's voice search: search for it and play the first.
    val spoken by voice.collectAsState()
    LaunchedEffect(spoken) {
        val q = spoken ?: return@LaunchedEffect
        tab = Tab.Search
        search.query = q
        val found = runCatching { VideoFeed.search(q) }.getOrDefault(emptyList())
        if (found.isNotEmpty()) {
            search.results = Load.Done(found)
            search.searched = q
            session.start(found, 0)
            playing = true
        }
        // Last, as clearing it restarts this effect.
        voice.value = null
    }

    val play: (List<Video>, Int) -> Unit = { list, i ->
        session.start(list, i)
        playing = true
    }

    if (playing) {
        PlayerScreen(session, onClose = {
            session.pause()
            playing = false
        })
        return
    }

    val homeTab = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(TvColors.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("OpenTune Videos", style = MaterialTheme.typography.titleLarge, color = TvColors.Accent)
            Spacer(Modifier.width(24.dp))
            Tab.entries.forEach { t ->
                TvPill(
                    label = t.label,
                    selected = tab == t,
                    onClick = { tab = t },
                    focusRequester = if (t == Tab.Home) homeTab else null,
                )
            }
            Spacer(Modifier.weight(1f))
            if (session.current != null) {
                TvButton("Now playing", onClick = { playing = true })
            }
        }
        Spacer(Modifier.height(4.dp))
        when (tab) {
            Tab.Home -> HomeScreen(rows, homeList, onRetry = { reload++ }, play = play)
            Tab.Search -> SearchScreen(search, play)
            Tab.Settings -> SettingsScreen()
        }
    }
    // Somewhere for the remote to start from.
    LaunchedEffect(Unit) { runCatching { homeTab.requestFocus() } }
}

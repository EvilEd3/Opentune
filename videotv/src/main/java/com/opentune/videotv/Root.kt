package com.opentune.videotv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Tab(val label: String) {
    Home("Home"),
    Search("Search"),
    Library("Library"),
    Queue("Queue"),
    Settings("Settings"),
}

/** A video picked from a list, waiting on the viewer's choice of what to do with it. */
private class Picked(val list: List<Video>, val index: Int) {
    val video: Video get() = list[index]
}

/**
 * The whole app: a bar of tabs over Home, Search, Library, Queue and
 * Settings, and the player full screen over all of it.
 *
 * Leaving the player doesn't stop the video: it carries on small in the top
 * bar while you browse. Picking a video while one plays asks whether to play
 * it now, next, or at the end of the queue. What each page has loaded is held
 * here, so coming back from a video finds it as it was.
 */
@Composable
fun Root(voice: MutableStateFlow<String?>, remote: TvRemote? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { VideoSession(context.applicationContext, scope) }
    DisposableEffect(session) { onDispose { session.release() } }

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var playing by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var queueItem by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeId by remember { mutableIntStateOf(0) }
    LaunchedEffect(noticeId) { if (notice != null) { delay(NOTICE_MS); notice = null } }
    fun tell(text: String) { notice = text; noticeId++ }

    // Leaving the TV app (Home button, input switch) pauses; nothing plays unseen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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
    val library = remember { LibraryState() }

    /** Picking a video: straight to it when nothing plays, else ask what to do. */
    val pick: (List<Video>, Int) -> Unit = { list, i ->
        if (session.current == null) {
            session.start(list, i)
            playing = true
        } else {
            picked = Picked(list, i)
        }
    }
    /** A whole list (a playlist): at the end of the queue, or right after the one playing. */
    val queueAll: (List<Video>, Boolean) -> Unit = { list, next ->
        val wasEmpty = session.current == null
        if (next) session.playNext(list) else session.addToQueue(list)
        if (wasEmpty) playing = true else tell(if (next) "Playing ${list.size} next" else "Added ${list.size} to the queue")
    }

    // A paired phone's trackpad and buttons act on this screen; what's playing goes back to it.
    val activity = LocalContext.current.findActivity()
    if (remote != null && activity != null) {
        val actions = remember(session) {
            RemoteActions(
                session = session,
                goHome = { playing = false; pairing = false; signingIn = false; tab = Tab.Home },
                search = { q -> playing = false; pairing = false; signingIn = false; tab = Tab.Search; search.query = q },
            )
        }
        val input = remember(activity, actions, remote) { TvRemoteInput(activity, remote.pointer, actions) }
        DisposableEffect(input) {
            remote.attach(input)
            onDispose { remote.detach(input) }
        }
        val phonesNow by remote.controllers.collectAsState()
        LaunchedEffect(phonesNow.isNotEmpty(), input) {
            while (phonesNow.isNotEmpty()) { remote.push(input.now()); delay(NOW_EVERY_MS) }
        }
    }

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

    Box(Modifier.fillMaxSize()) {
        when {
            // Back from the player keeps the video going, small, in the top bar.
            playing -> PlayerScreen(session, onClose = { playing = false })
            pairing && remote != null -> PairPhoneScreen(remote, onClose = { pairing = false })
            signingIn -> SignInScreen(onClose = { signingIn = false; library.reload++ })
            else -> Browse(
                tab = tab,
                onTab = { tab = it },
                session = session,
                onNowPlaying = { playing = true },
                rows = rows,
                homeList = homeList,
                onRetry = { reload++ },
                search = search,
                library = library,
                onSignIn = { signingIn = true },
                remote = remote,
                onPair = { pairing = true },
                pick = pick,
                queueAll = queueAll,
                onQueueItem = { queueItem = it },
            )
        }

        picked?.let { p ->
            val video = p.video
            val rest = p.list.drop(p.index)
            ActionMenu(
                title = video.title,
                subtitle = video.artist,
                actions = listOf(
                    MenuAction("Play now", primary = true) {
                        session.playNow(video)
                        playing = true
                    },
                    MenuAction("Play next") { session.playNext(listOf(video)); tell("Playing next: ${video.title}") },
                    MenuAction("Add to queue") { session.addToQueue(listOf(video)); tell("Added to queue: ${video.title}") },
                    MenuAction(if (rest.size > 1) "Play from here (replaces the queue)" else "Play only this (replaces the queue)") {
                        session.start(p.list, p.index)
                        playing = true
                    },
                ),
                onDismiss = { picked = null },
            )
        }

        queueItem?.let { i ->
            val video = session.queue.getOrNull(i)
            if (video == null) {
                queueItem = null
            } else {
                ActionMenu(
                    title = video.title,
                    subtitle = video.artist,
                    actions = buildList {
                        if (i != session.index) {
                            add(MenuAction("Play now", primary = true) { session.jumpTo(i); playing = true })
                            add(MenuAction("Remove from queue") { session.removeAt(i) })
                        } else {
                            add(MenuAction("Watch full screen", primary = true) { playing = true })
                        }
                    },
                    onDismiss = { queueItem = null },
                )
            }
        }

        Notice(notice, Modifier.align(Alignment.BottomCenter))

        // The phone's pointer, over everything.
        if (remote != null) TvCursor(remote.pointer)
    }
}

@Composable
private fun Browse(
    tab: Tab,
    onTab: (Tab) -> Unit,
    session: VideoSession,
    onNowPlaying: () -> Unit,
    rows: Load<List<VideoRow>>,
    homeList: LazyListState,
    onRetry: () -> Unit,
    search: SearchState,
    library: LibraryState,
    onSignIn: () -> Unit,
    remote: TvRemote?,
    onPair: () -> Unit,
    pick: (List<Video>, Int) -> Unit,
    queueAll: (List<Video>, Boolean) -> Unit,
    onQueueItem: (Int) -> Unit,
) {
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
                if (t == Tab.Queue && session.queue.isEmpty()) return@forEach
                TvPill(
                    label = if (t == Tab.Queue) "Queue (${session.queue.size - session.index - 1})" else t.label,
                    selected = tab == t,
                    onClick = { onTab(t) },
                    focusRequester = if (t == Tab.Home) homeTab else null,
                )
            }
            Spacer(Modifier.weight(1f))
            MiniPlayer(session, onOpen = onNowPlaying)
        }
        Spacer(Modifier.height(4.dp))
        when (tab) {
            Tab.Home -> HomeScreen(rows, homeList, onRetry = onRetry, play = pick)
            Tab.Search -> SearchScreen(search, pick)
            Tab.Library -> LibraryScreen(library, onSignIn, pick, queueAll)
            Tab.Queue -> QueueScreen(session, onOpen = onQueueItem, onClearUpcoming = { session.clearUpcoming() })
            Tab.Settings -> SettingsScreen(remote, onPair, onSignIn)
        }
    }
    // Somewhere for the remote to start from.
    LaunchedEffect(Unit) { runCatching { homeTab.requestFocus() } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How often a connected phone is told what's playing. */
private const val NOW_EVERY_MS = 700L

/** How long "Added to queue" stays up. */
private const val NOTICE_MS = 2_500L

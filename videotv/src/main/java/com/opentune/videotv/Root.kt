package com.opentune.videotv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
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
import com.opentune.data.account.AccountStore
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
fun Root(
    voice: MutableStateFlow<String?>,
    remote: TvRemote? = null,
    playlistQuery: MutableStateFlow<String?> = MutableStateFlow(null),
    testVideo: MutableStateFlow<String?> = MutableStateFlow(null),
    testSearch: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { VideoSession(context.applicationContext, scope) }
    DisposableEffect(session) { onDispose { session.release() } }

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var playing by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Video?>(null) }
    /** A video to save to a playlist: the playlist picker is open for it. */
    var saving by remember { mutableStateOf<Video?>(null) }
    /** A song whose video is wrong: the chooser of other videos is open for it. */
    var fixing by remember { mutableStateOf<Video?>(null) }
    /** Back on Home: asking whether to leave the app. */
    var leaving by remember { mutableStateOf(false) }
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

    /**
     * Picking a video: just that one (autoplay finds what follows it), straight
     * away when nothing plays, else a menu of what to do with it.
     */
    val pick: (Video) -> Unit = { video ->
        if (session.current == null) {
            session.start(listOf(video), 0)
            playing = true
        } else {
            picked = video
        }
    }
    /** Holding OK on a video: onto the end of the queue, no questions. */
    val quickAdd: (Video) -> Unit = { video ->
        if (session.current == null) {
            session.start(listOf(video), 0)
            playing = true
        } else {
            session.addToQueue(listOf(video))
            tell("Added to queue: ${video.title}")
        }
    }
    /** A playlist's Play or Shuffle: the whole list, in place of the queue. */
    val playAll: (List<Video>) -> Unit = { list ->
        if (list.isNotEmpty()) {
            session.start(list, 0)
            playing = true
        }
    }
    val openPlaylist: (PlaylistCard) -> Unit = { card ->
        library.open = card
        tab = Tab.Library
    }
    val signedIn by AccountStore.signedIn.collectAsState()
    /** Like and Save, for menus, when there is an account to keep them in. */
    fun accountActions(video: Video): List<MenuAction> = if (!signedIn) emptyList() else listOf(
        MenuAction(if (video.videoId in session.liked) "Unlike" else "Like") { session.toggleLike(video) { tell(it) } },
        MenuAction("Add to a playlist…") { saving = video },
    )
    /** For a song played as a video: a way to pick the right video when the match is wrong. */
    fun fixAction(video: Video): List<MenuAction> =
        if (video.songId != null || !video.isVideo) listOf(MenuAction("Wrong video? Choose another…") { fixing = video }) else emptyList()
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

    // A playlist asked for by search (the automated test's way in): open the first found.
    val wantedPlaylist by playlistQuery.collectAsState()
    LaunchedEffect(wantedPlaylist) {
        val q = wantedPlaylist ?: return@LaunchedEffect
        val card = runCatching { VideoFeed.findPlaylist(q) }.getOrNull()
        if (card != null) {
            playing = false
            tab = Tab.Library
            library.open = card
        }
        playlistQuery.value = null
    }

    // The automated test's way to the Search page, with something typed in.
    val wantedSearch by testSearch.collectAsState()
    LaunchedEffect(wantedSearch) {
        val q = wantedSearch ?: return@LaunchedEffect
        playing = false
        tab = Tab.Search
        search.query = q
        testSearch.value = null
    }

    // The automated test's own clip: played like any video, so the controls can be tried on it.
    val testUrl by testVideo.collectAsState()
    LaunchedEffect(testUrl) {
        val url = testUrl ?: return@LaunchedEffect
        session.start(listOf(Video("test-clip", "Test video", "OpenTune test", directUrl = url)), 0)
        playing = true
        testVideo.value = null
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
            // Just the first, like any pick; autoplay finds what follows.
            session.start(listOf(found.first()), 0)
            playing = true
        }
        // Last, as clearing it restarts this effect.
        voice.value = null
    }

    Box(Modifier.fillMaxSize()) {
        if (playing) {
            PlayerScreen(
                session,
                onClose = { playing = false },
                signedIn = signedIn,
                onSave = { saving = it },
                onFix = { fixing = it },
                tell = { tell(it) },
            )
        } else {
            // Browsing: the pages, with what's playing at the end of the tab bar.
            Box(Modifier.fillMaxSize().background(TvColors.Background)) {
                when {
                    pairing && remote != null -> PairPhoneScreen(remote, onClose = { pairing = false })
                    signingIn -> SignInScreen(onClose = { signingIn = false; library.reload++; reload++ })
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
                        quickAdd = quickAdd,
                        playAll = playAll,
                        queueAll = queueAll,
                        openPlaylist = openPlaylist,
                        onQueueItem = { queueItem = it },
                        onBackAtHome = { leaving = true },
                    )
                }
            }
        }

        picked?.let { video ->
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
                ) + fixAction(video) + accountActions(video),
                onDismiss = { picked = null },
            )
        }

        fixing?.let { video ->
            VideoChooser(
                song = video,
                onChoose = { chosen ->
                    val fixed = VideoFeed.choose(video, chosen)
                    if (session.current?.let { (it.songId ?: it.videoId) == (video.songId ?: video.videoId) } == true) {
                        session.replaceCurrent(fixed)
                    } else {
                        session.replaceQueued(video, fixed)
                    }
                    tell(if (chosen == null) "“${video.title}” will play without a video" else "“${video.title}” will play this video from now on")
                },
                onDismiss = { fixing = null },
            )
        }

        if (leaving) {
            val activity = LocalContext.current.findActivity()
            ActionMenu(
                title = "Leave OpenTune Videos?",
                subtitle = if (session.current != null) "The video will stop." else null,
                actions = buildList {
                    if (session.current != null) add(MenuAction("Back to the video", primary = true) { playing = true })
                    add(MenuAction("Exit", primary = session.current == null) { session.pause(); activity?.finish() })
                },
                onDismiss = { leaving = false },
            )
        }

        saving?.let { video ->
            PlaylistPicker(
                video = video,
                library = library,
                onPick = { card -> session.save(video, card) { tell(it) } },
                onDismiss = { saving = null },
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
                            if (i > session.index + 1) add(MenuAction("Move up") { session.move(i, -1) })
                            if (i > session.index && i < session.queue.lastIndex) add(MenuAction("Move down") { session.move(i, 1) })
                            add(MenuAction("Remove from queue") { session.removeAt(i) })
                        } else {
                            add(MenuAction("Watch full screen", primary = true) { playing = true })
                        }
                        addAll(fixAction(video))
                        addAll(accountActions(video))
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
    pick: (Video) -> Unit,
    quickAdd: (Video) -> Unit,
    playAll: (List<Video>) -> Unit,
    queueAll: (List<Video>, Boolean) -> Unit,
    openPlaylist: (PlaylistCard) -> Unit,
    onQueueItem: (Int) -> Unit,
    onBackAtHome: () -> Unit,
) {
    // Back never leaves the app by itself: on another tab it goes to Home, and
    // on Home it asks (with "Back to the video" first, when one is playing).
    // Pages with somewhere of their own to go back to (a playlist) handle Back first.
    BackHandler { if (tab != Tab.Home) onTab(Tab.Home) else onBackAtHome() }
    val homeTab = remember { FocusRequester() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal, vertical = TvSafe.Vertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.current == null) {
                Text("OpenTune Videos", style = MaterialTheme.typography.titleLarge, color = TvColors.Accent)
                Spacer(Modifier.width(24.dp))
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Tab.entries.forEach { t ->
                    if (t == Tab.Queue && session.queue.isEmpty()) return@forEach
                    TvPill(
                        label = if (t == Tab.Queue) "Queue ${session.queue.size - session.index - 1}" else t.label,
                        selected = tab == t,
                        onClick = { onTab(t) },
                        // Settings as a gear, so every tab fits beside the now-playing card.
                        icon = if (t == Tab.Settings) Icons.Rounded.Settings else null,
                        focusRequester = if (t == Tab.Home) homeTab else null,
                    )
                }
            }
            NowPlayingPanel(session, onOpen = onNowPlaying, modifier = Modifier.width(NOW_PLAYING_WIDTH))
        }
        Spacer(Modifier.height(4.dp))
        when (tab) {
            Tab.Home -> HomeScreen(rows, homeList, onRetry = onRetry, pick = pick, quickAdd = quickAdd, openPlaylist = openPlaylist)
            Tab.Search -> SearchScreen(search, pick, quickAdd, openPlaylist)
            Tab.Library -> LibraryScreen(library, onSignIn, pick, quickAdd, playAll, queueAll)
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

/** The now-playing card at the end of the tab bar. */
private val NOW_PLAYING_WIDTH = 300.dp

/** How long "Added to queue" stays up. */
private const val NOTICE_MS = 2_500L

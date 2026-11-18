package com.opentune.videotv

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.account.AccountStore
import com.opentune.data.account.PairingReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the Library tab holds on to while the player is open. */
class LibraryState {
    var playlists by mutableStateOf<Load<List<PlaylistCard>>?>(null)
    var open by mutableStateOf<PlaylistCard?>(null)
    var tracks by mutableStateOf<Load<List<Video>>>(Load.Loading)
    var tracksOf by mutableStateOf<String?>(null)
    var reload by mutableIntStateOf(0)
}

/**
 * The signed-in account's playlists, Liked music first; signing in with the
 * phone when there is no account on this TV yet.
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    onSignIn: () -> Unit,
    pick: (List<Video>, Int) -> Unit,
    queueAll: (List<Video>, Boolean) -> Unit,
) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()

    if (!signedIn) {
        Column(Modifier.padding(horizontal = TvSafe.Horizontal, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your playlists", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sign in to play the playlists and liked songs in your YouTube Music account, as music videos. " +
                    "You sign in with the OpenTune app on your phone, by scanning a code — no typing.",
                style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim,
            )
            TvButton("Sign in with your phone", onSignIn, primary = true)
        }
        return
    }

    val open = state.open
    if (open != null) {
        PlaylistPage(state, open, pick, queueAll)
        return
    }

    LaunchedEffect(signedIn, state.reload) {
        if (state.playlists is Load.Done && state.reload == 0) return@LaunchedEffect
        state.playlists = Load.Loading
        state.playlists = try {
            Load.Done(VideoFeed.playlists())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                account?.name?.let { "$it's playlists" } ?: "Your playlists",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
            )
            TvButton("Refresh", { state.reload++ })
        }
        Spacer(Modifier.height(12.dp))
        when (val p = state.playlists) {
            null, Load.Loading -> TvNote("Loading your playlists…")
            Load.Failed -> TvNote("Couldn't load your playlists. Check the connection, then Refresh.")
            is Load.Done -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(p.value, key = { it.browseId }) { card ->
                    PlaylistTile(card, onClick = { state.open = card })
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(card: PlaylistCard, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth()) {
        TvSurface(onClick = onClick, shape = shape, scale = 1.06f, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val art = card.thumbnail
            if (art != null) {
                Cover(art, Modifier.matchParentSize(), shape)
            } else {
                Box(Modifier.matchParentSize().background(TvColors.Accent.copy(alpha = 0.25f), shape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(56.dp), tint = TvColors.Accent)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One playlist: play it, shuffle it, add it all to the queue, or pick a track. */
@Composable
private fun PlaylistPage(
    state: LibraryState,
    card: PlaylistCard,
    pick: (List<Video>, Int) -> Unit,
    queueAll: (List<Video>, Boolean) -> Unit,
) {
    BackHandler { state.open = null }
    LaunchedEffect(card.browseId) {
        if (state.tracksOf == card.browseId && state.tracks is Load.Done) return@LaunchedEffect
        state.tracksOf = card.browseId
        state.tracks = Load.Loading
        state.tracks = try {
            Load.Done(VideoFeed.playlist(card.browseId))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = (state.tracks as? Load.Done)?.value?.size
                Text(
                    if (count != null) "$count tracks · songs play as their music videos" else card.subtitle,
                    style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
                )
            }
            val tracks = (state.tracks as? Load.Done)?.value.orEmpty()
            if (tracks.isNotEmpty()) {
                TvButton("Play", { pick(tracks, 0) }, icon = Icons.Rounded.PlayArrow, primary = true, focusRequester = first)
                TvButton("Shuffle", { pick(tracks.shuffled(), 0) }, icon = Icons.Rounded.Shuffle)
                TvButton("Add all to queue", { queueAll(tracks, false) }, icon = Icons.AutoMirrored.Rounded.QueueMusic)
            }
            TvButton("Back", { state.open = null })
        }
        Spacer(Modifier.height(12.dp))
        when (val t = state.tracks) {
            Load.Loading -> TvNote("Loading…")
            Load.Failed -> TvNote("Couldn't load this playlist.")
            is Load.Done -> {
                LaunchedEffect(t.value.isNotEmpty()) { if (t.value.isNotEmpty()) runCatching { first.requestFocus() } }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = TvSafe.Horizontal - 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(t.value, key = { _, v -> v.videoId }) { i, video ->
                        TrackRow(video, onClick = { pick(t.value, i) })
                    }
                }
            }
        }
    }
}

/** A track in a list: its picture, title and artist, and how long it is. */
@Composable
fun TrackRow(video: Video, onClick: () -> Unit, highlight: Boolean = false, note: String? = null) {
    val shape = RoundedCornerShape(12.dp)
    TvSurface(onClick = onClick, shape = shape, scale = 1.015f, modifier = Modifier.fillMaxWidth()) { _ ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (highlight) TvColors.Accent.copy(alpha = 0.18f) else Color.Transparent, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(video.thumbnail, Modifier.width(128.dp).aspectRatio(16f / 9f), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(video.artist, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (note != null) Text(note, style = MaterialTheme.typography.labelLarge, color = TvColors.Accent, modifier = Modifier.padding(horizontal = 12.dp))
            video.duration?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim) }
        }
    }
}

/**
 * Signing in without typing: a code on the screen that the phone's OpenTune
 * scans, which then hands this TV its YouTube Music session directly over the
 * home Wi-Fi, encrypted.
 */
@Composable
fun SignInScreen(onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var receiver by remember { mutableStateOf<PairingReceiver?>(null) }
    var status by remember { mutableStateOf("Preparing a private connection…") }
    var done by remember { mutableStateOf(false) }
    val signedIn by AccountStore.signedIn.collectAsState()

    BackHandler(onBack = onClose)
    DisposableEffect(attempt) { onDispose { receiver?.close() } }
    LaunchedEffect(attempt) {
        receiver = null
        done = false
        try {
            val current = withContext(Dispatchers.IO) { PairingReceiver() }
            receiver = current
            status = "On your phone, open OpenTune, go to Settings, then Link a device, then TV, and scan this code. " +
                "Both must be on the same Wi-Fi. It lasts two minutes."
            withContext(Dispatchers.IO) { current.receive { cookie -> AccountStore.signIn(cookie); true } }
            done = true
            status = "Signed in. Your YouTube Music account is on this TV."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = if (e.message?.contains("same Wi-Fi") == true) {
                "Connect this TV and your phone to the same Wi-Fi network."
            } else {
                "That code ran out, or the phone couldn't reach this TV. Make a new one."
            }
        } finally {
            receiver?.close()
            receiver = null
        }
    }
    LaunchedEffect(signedIn, done) { if (done && signedIn) { delay(1_800); onClose() } }

    Row(
        Modifier.fillMaxSize().background(TvColors.Background).padding(horizontal = TvSafe.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Box(
            Modifier.size(360.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val link = receiver?.offer?.link
            if (link != null && !done) QrCode(link, Modifier.fillMaxSize())
            else Text(if (done) "✓" else "…", style = MaterialTheme.typography.displaySmall, color = Color.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Sign in with your phone", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (receiver == null && !done) TvButton("New code", { attempt++ }, primary = true)
                TvButton(if (done) "Done" else "Cancel", onClose)
            }
        }
    }
}

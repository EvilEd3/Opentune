package com.opentune.videotv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opentune.data.remote.RemoteInvite
import kotlinx.coroutines.delay

/**
 * Settings' "Phone remote" section: the phones paired with this TV, and the
 * way to pair another. Pairing is OpenTune's own: the phone app scans a code
 * once, then finds this TV by itself on the same Wi-Fi.
 */
@Composable
fun PhoneRemoteSettings(remote: TvRemote, onPair: () -> Unit) {
    val phones by remote.phones.phones.collectAsState()
    val connected by remote.controllers.collectAsState()
    Column {
        Text("Phone remote", style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                connected.isNotEmpty() -> "Connected now: ${connected.joinToString()}."
                phones.isEmpty() -> "Use the OpenTune phone app as a remote, with a trackpad and keyboard. Pair it once."
                else -> "Open OpenTune on a paired phone, then Settings, Link a device, TV, to control this TV."
            },
            style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Pair a phone", onPair, primary = true)
            phones.forEach { phone ->
                TvButton("Forget ${phone.name}", { remote.forget(phone) })
            }
        }
    }
}

/**
 * Pairing a phone: a code on the screen that the phone scans once. After that
 * the phone finds this TV by itself. The code lasts two minutes and works for
 * one phone.
 */
@Composable
fun PairPhoneScreen(remote: TvRemote, onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val before = remember { remote.phones.phones.value.size }
    val phones by remote.phones.phones.collectAsState()
    val done = phones.size > before

    BackHandler(onBack = onClose)
    DisposableEffect(Unit) { onDispose { remote.cancelInvite() } }
    LaunchedEffect(attempt) {
        problem = null
        link = runCatching { remote.invite().link }.onFailure { problem = it.message }.getOrNull()
        // The code runs out after two minutes; say so instead of leaving a dead one up.
        if (link != null) { delay(RemoteInvite.LIFETIME_MS); link = null }
    }
    LaunchedEffect(done) { if (done) { delay(1_800); onClose() } }

    Row(
        Modifier.fillMaxSize().background(TvColors.Background).padding(horizontal = TvSafe.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(64.dp),
    ) {
        Box(
            Modifier.size(360.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val code = link
            if (code != null && !done) QrCode(code, Modifier.fillMaxSize())
            else Text(if (done) "✓" else "…", style = MaterialTheme.typography.displaySmall, color = Color.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Pair a phone", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    done -> "Paired. Open OpenTune on the phone, then Settings, Link a device, TV, to use it as a remote."
                    problem != null -> problem.orEmpty()
                    link == null -> "That code ran out. Make a new one."
                    else -> "On your phone, open OpenTune, then Settings, Link a device, TV, Control this TV, " +
                        "and scan this code. Both must be on the same Wi-Fi. It lasts two minutes."
                },
                style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (link == null && !done) TvButton("New code", { attempt++ }, primary = true)
                TvButton(if (done) "Done" else "Cancel", onClose)
            }
        }
    }
}

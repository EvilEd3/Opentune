package com.opentune.videotv

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.opentune.data.remote.PairedPhones
import com.opentune.data.remote.PrefsStorage
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** What was asked of the TV's voice search ("play … on OpenTune Videos"), until Root picks it up. */
    private val voice = MutableStateFlow<String?>(null)

    /**
     * A playlist to open, found by search: how the automated TV test reaches a
     * playlist page without an account (`--es opentune.test.playlist_query …`).
     */
    private val playlistQuery = MutableStateFlow<String?>(null)
    private lateinit var remote: TvRemote

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice.value = spokenQuery(intent)
        playlistQuery.value = intent?.getStringExtra(EXTRA_PLAYLIST_QUERY)
        // Paired phones find this TV and control it for as long as the app is open here.
        remote = TvRemote(this, PairedPhones(PrefsStorage(this, "remote_phones")))
        remote.start()
        setContent { TvTheme { Root(voice, remote, playlistQuery) } }
    }

    override fun onDestroy() {
        remote.stop()
        super.onDestroy()
    }

    /** Keys go past [KeyHook] first: the player takes Down for its Up next panel. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        KeyHook.handler?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        spokenQuery(intent)?.let { voice.value = it }
        intent.getStringExtra(EXTRA_PLAYLIST_QUERY)?.let { playlistQuery.value = it }
    }

    override fun onStop() {
        super.onStop()
        com.opentune.data.Core.flush()
    }

    private fun spokenQuery(intent: Intent?): String? =
        intent?.takeIf { it.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH }
            ?.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
}

private const val EXTRA_PLAYLIST_QUERY = "opentune.test.playlist_query"

/** A screen's first look at a key, before the focused view gets it (see [MainActivity.dispatchKeyEvent]). */
object KeyHook {
    var handler: ((android.view.KeyEvent) -> Boolean)? = null
}

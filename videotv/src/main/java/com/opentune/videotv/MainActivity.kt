package com.opentune.videotv

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** What was asked of the TV's voice search ("play … on OpenTune Videos"), until Root picks it up. */
    private val voice = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice.value = spokenQuery(intent)
        setContent { TvTheme { Root(voice) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        spokenQuery(intent)?.let { voice.value = it }
    }

    override fun onStop() {
        super.onStop()
        com.opentune.data.Core.flush()
    }

    private fun spokenQuery(intent: Intent?): String? =
        intent?.takeIf { it.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH }
            ?.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
}

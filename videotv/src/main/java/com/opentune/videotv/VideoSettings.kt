package com.opentune.videotv

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.opentune.data.innertube.VideoStreams

/** The ceiling on picture size. Above 1080p YouTube only offers VP9, so those need a VP9 decoder. */
enum class VideoQuality(val label: String, val maxHeight: Int) {
    Q480("480p", 480),
    Q720("720p", 720),
    Q1080("1080p", 1080),
    Q1440("1440p", 1440),
    Q2160("4K", 2160),
}

/** How big everything on screen is drawn. Large is the size the app first had. */
enum class UiSize(val label: String, val scale: Float) {
    Small("Small", 0.65f),
    Medium("Medium", 0.8f),
    Large("Large", 1f),
}

/** What the viewer has chosen, kept between launches. */
object VideoSettings {
    private lateinit var prefs: SharedPreferences

    var quality by mutableStateOf(VideoQuality.Q1080)
        private set
    var autoplay by mutableStateOf(true)
        private set
    var size by mutableStateOf(UiSize.Medium)
        private set

    /** Whether this TV decodes VP9 in hardware: what makes 1440p and 4K worth offering. */
    var hasHardwareVp9 = false
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
        hasHardwareVp9 = runCatching { detectHardwareVp9() }.getOrDefault(false)
        quality = prefs.getString("quality", null)
            ?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
            ?: VideoQuality.Q1080
        if (quality.maxHeight > 1080 && !hasHardwareVp9) quality = VideoQuality.Q1080
        autoplay = prefs.getBoolean("autoplay", true)
        size = prefs.getString("size", null)?.let { n -> UiSize.entries.firstOrNull { it.name == n } } ?: UiSize.Medium
    }

    fun chooseSize(value: UiSize) {
        size = value
        prefs.edit().putString("size", value.name).apply()
    }

    fun choices(): List<VideoQuality> =
        VideoQuality.entries.filter { it.maxHeight <= 1080 || hasHardwareVp9 }

    fun chooseQuality(value: VideoQuality) {
        quality = value
        prefs.edit().putString("quality", value.name).apply()
    }

    fun chooseAutoplay(value: Boolean) {
        autoplay = value
        prefs.edit().putBoolean("autoplay", value).apply()
    }

    /** What to ask the resolver for. VP9 only where it decodes in hardware; H.264 always works. */
    fun request(): VideoStreams.Request =
        VideoStreams.Request(maxHeight = quality.maxHeight, allowVp9 = hasHardwareVp9 && quality.maxHeight > 1080)

    private fun detectHardwareVp9(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals("video/x-vnd.on2.vp9", ignoreCase = true) } &&
                isHardware(info)
        }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
}

package com.opentune.videotv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.opentune.data.Core
import com.opentune.data.Http
import com.opentune.data.MusicRepository
import okio.Path.Companion.toOkioPath

class VideoApp : Application(), SingletonImageLoader.Factory {

    /** Thumbnails are drawn large on a TV, so they get a fair share of memory and disk. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(250)
            .build()

    override fun onCreate() {
        super.onCreate()
        com.opentune.data.CrashLog.install(this)
        // phone = false: no update checks, no usage counts, no download workers.
        Core.init(this, phone = false)
        com.opentune.i18n.Tr.use(this, "")
        MusicRepository.init(this)
        VideoSettings.init(this)
        VideoFeed.init(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.opentune.i18n.Tr.use(this, "")
    }
}

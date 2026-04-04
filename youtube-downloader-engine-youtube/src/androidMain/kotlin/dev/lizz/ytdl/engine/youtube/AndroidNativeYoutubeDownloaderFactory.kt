package dev.lizz.ytdl.engine.youtube

import android.content.Context
import dev.lizz.ytdl.core.DefaultYoutubeDownloader
import dev.lizz.ytdl.core.YoutubeDownloader

/** Creates the default Android downloader backed by the native YouTube engine. */
public object AndroidNativeYoutubeDownloaderFactory {
    /** Builds the default downloader using the application context. */
    public fun create(context: Context): YoutubeDownloader {
        return DefaultYoutubeDownloader(AndroidNativeYoutubeDownloadEngine(context.applicationContext))
    }
}

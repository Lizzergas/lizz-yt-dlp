package com.lizz.ytdl.engine.youtube

import android.content.Context
import com.lizz.ytdl.core.DefaultYoutubeDownloader
import com.lizz.ytdl.core.YoutubeDownloader

object AndroidNativeYoutubeDownloaderFactory {
    fun create(context: Context): YoutubeDownloader {
        return DefaultYoutubeDownloader(AndroidNativeYoutubeDownloadEngine(context.applicationContext))
    }
}

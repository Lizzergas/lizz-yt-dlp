package com.lizz.ytdl.engine.youtube

import com.lizz.ytdl.core.DefaultYoutubeDownloader
import com.lizz.ytdl.core.YoutubeDownloader

object JvmNativeYoutubeDownloaderFactory {
    fun createDefault(): YoutubeDownloader {
        return DefaultYoutubeDownloader(JvmNativeYoutubeDownloadEngine())
    }
}

package com.lizz.ytdl.engine.youtube

import com.lizz.ytdl.core.DefaultYoutubeDownloader
import com.lizz.ytdl.core.YoutubeDownloader

object IosNativeYoutubeDownloaderFactory {
    fun createDefault(): YoutubeDownloader {
        return DefaultYoutubeDownloader(IosNativeYoutubeDownloadEngine())
    }
}

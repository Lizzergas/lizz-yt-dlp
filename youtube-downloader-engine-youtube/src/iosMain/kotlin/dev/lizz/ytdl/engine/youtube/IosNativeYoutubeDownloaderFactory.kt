package dev.lizz.ytdl.engine.youtube

import dev.lizz.ytdl.core.DefaultYoutubeDownloader
import dev.lizz.ytdl.core.YoutubeDownloader

/** Creates the default iOS downloader backed by the native YouTube engine. */
public object IosNativeYoutubeDownloaderFactory {
    /** Builds the default downloader for iOS targets. */
    public fun createDefault(): YoutubeDownloader {
        return DefaultYoutubeDownloader(IosNativeYoutubeDownloadEngine())
    }
}

package dev.lizz.ytdl.engine.youtube

import dev.lizz.ytdl.core.DefaultYoutubeDownloader
import dev.lizz.ytdl.core.YoutubeDownloader

/** Creates the default JVM downloader backed by the native YouTube engine. */
public object JvmNativeYoutubeDownloaderFactory {
    /** Builds the default downloader for JVM and Desktop targets. */
    public fun createDefault(): YoutubeDownloader {
        return DefaultYoutubeDownloader(JvmNativeYoutubeDownloadEngine())
    }
}

package dev.lizz.ytdl.smoketest

import dev.lizz.ytdl.core.DownloadOptions
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory

@Suppress("unused")
fun buildRequest(): Pair<DownloadRequest, Any> {
    val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()
    val request = DownloadRequest(
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        options = DownloadOptions(outputPath = "./downloads"),
    )
    return request to downloader
}

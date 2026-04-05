package dev.lizz.ytdl.smoketest

import dev.lizz.ytdl.core.AudioDownloadOptions
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.providers.youtube.JvmYoutubeProviderFactory

@Suppress("unused")
fun buildRequest(): Pair<AudioDownloadRequest, Any> {
    val downloader = JvmYoutubeProviderFactory.createDefault()
    val request = AudioDownloadRequest(
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        options = AudioDownloadOptions(outputPath = "./downloads"),
    )
    return request to downloader
}

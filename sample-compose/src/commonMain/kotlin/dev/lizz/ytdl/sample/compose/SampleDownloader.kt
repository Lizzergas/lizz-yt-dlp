package dev.lizz.ytdl.sample.compose

import dev.lizz.ytdl.core.DownloadEvent
import dev.lizz.ytdl.core.DownloadOptions
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.core.DownloadResult
import dev.lizz.ytdl.core.TranscriptResult
import io.github.vinceglb.filekit.PlatformFile

interface SampleDownloader {
    val platformName: String
    val isSupported: Boolean

    suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult

    suspend fun getTranscript(url: String, includeTimecodes: Boolean = false): String?

    suspend fun getTranscriptCues(url: String): TranscriptResult?
}

expect fun createSampleDownloader(): SampleDownloader
expect fun initializePlatformFileKit()
expect suspend fun revealDownloadedDirectory(resultPath: String?, selectedDirectory: PlatformFile?): Boolean

fun buildDownloadRequest(url: String, outputPath: String): DownloadRequest {
    return DownloadRequest(
        url = url,
        options = DownloadOptions(
            outputPath = outputPath.takeIf { it.isNotBlank() },
        ),
    )
}

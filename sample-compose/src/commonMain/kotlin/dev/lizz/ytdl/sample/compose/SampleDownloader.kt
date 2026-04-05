package dev.lizz.ytdl.sample.compose

import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.AudioDownloadOptions
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.core.AudioDownloadResult
import dev.lizz.ytdl.core.TranscriptRequest
import dev.lizz.ytdl.core.TranscriptResult
import io.github.vinceglb.filekit.PlatformFile

interface SampleDownloader {
    val platformName: String
    val isSupported: Boolean

    suspend fun download(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit = {},
    ): AudioDownloadResult

    suspend fun getTranscript(url: String, includeTimecodes: Boolean = false): String?

    suspend fun getTranscriptCues(url: String): TranscriptResult?
}

expect fun createSampleDownloader(): SampleDownloader
expect fun initializePlatformFileKit()
expect suspend fun revealDownloadedDirectory(resultPath: String?, selectedDirectory: PlatformFile?): Boolean

fun buildDownloadRequest(url: String, outputPath: String): AudioDownloadRequest {
    return AudioDownloadRequest(
        url = url,
        options = AudioDownloadOptions(
            outputPath = outputPath.takeIf { it.isNotBlank() },
        ),
    )
}

fun buildTranscriptRequest(url: String, includeTimecodes: Boolean): TranscriptRequest {
    return TranscriptRequest(
        url = url,
        includeTimecodes = includeTimecodes,
    )
}

package com.lizz.ytdl.sample.compose

import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.DownloadOptions
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadResult
import io.github.vinceglb.filekit.PlatformFile

interface SampleDownloader {
    val platformName: String
    val isSupported: Boolean

    suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
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

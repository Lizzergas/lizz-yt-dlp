package com.lizz.ytdl.sample.compose

import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadResult

actual fun createSampleDownloader(): SampleDownloader = IosSampleDownloader()

private class IosSampleDownloader : SampleDownloader {
    override val platformName: String = "iOS"
    override val isSupported: Boolean = false

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult {
        throw UnsupportedOperationException(
            "The iOS-native downloader/transcoder implementation is not finished yet. The shared KMP module is ready for adding it."
        )
    }
}

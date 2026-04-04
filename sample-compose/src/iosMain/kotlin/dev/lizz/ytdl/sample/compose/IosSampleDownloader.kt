package dev.lizz.ytdl.sample.compose

import dev.lizz.ytdl.core.DownloadEvent
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.core.DownloadResult
import dev.lizz.ytdl.core.YoutubeDownloader
import dev.lizz.ytdl.engine.youtube.IosNativeYoutubeDownloaderFactory

actual fun createSampleDownloader(): SampleDownloader = IosSampleDownloader(IosNativeYoutubeDownloaderFactory.createDefault())
actual fun initializePlatformFileKit() = Unit
actual suspend fun revealDownloadedDirectory(resultPath: String?, selectedDirectory: io.github.vinceglb.filekit.PlatformFile?): Boolean = false

private class IosSampleDownloader(
    private val delegate: YoutubeDownloader,
) : SampleDownloader {
    override val platformName: String = "iOS"
    override val isSupported: Boolean = true

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult {
        return delegate.download(request, emit)
    }
}

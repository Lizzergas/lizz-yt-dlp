package dev.lizz.ytdl.sample.compose

import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.core.AudioDownloadResult
import dev.lizz.ytdl.core.TranscriptResult
import dev.lizz.ytdl.core.MediaClient
import dev.lizz.ytdl.providers.youtube.IosYoutubeProviderFactory

actual fun createSampleDownloader(): SampleDownloader = IosSampleDownloader(IosYoutubeProviderFactory.createDefault())
actual fun initializePlatformFileKit() = Unit
actual suspend fun revealDownloadedDirectory(resultPath: String?, selectedDirectory: io.github.vinceglb.filekit.PlatformFile?): Boolean = false

private class IosSampleDownloader(
    private val delegate: MediaClient,
) : SampleDownloader {
    override val platformName: String = "iOS"
    override val isSupported: Boolean = true

    override suspend fun download(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit,
    ): AudioDownloadResult {
        return delegate.downloadAudio(request, emit)
    }

    override suspend fun getTranscript(url: String, includeTimecodes: Boolean): String? {
        return delegate.getTranscript(buildTranscriptRequest(url, includeTimecodes))
    }

    override suspend fun getTranscriptCues(url: String): TranscriptResult? {
        return delegate.getTranscriptCues(buildTranscriptRequest(url, includeTimecodes = false))
    }
}

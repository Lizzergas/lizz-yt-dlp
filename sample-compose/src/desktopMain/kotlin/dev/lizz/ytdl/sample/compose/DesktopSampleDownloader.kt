package dev.lizz.ytdl.sample.compose

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.core.AudioDownloadResult
import dev.lizz.ytdl.core.TranscriptResult
import dev.lizz.ytdl.providers.youtube.JvmYoutubeProviderFactory
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import java.awt.Desktop
import java.io.File

actual fun createSampleDownloader(): SampleDownloader = DesktopSampleDownloader()
actual fun initializePlatformFileKit() {
    FileKit.init(appId = "dev.lizz.ytdl.sample.compose")
}

actual suspend fun revealDownloadedDirectory(resultPath: String?, selectedDirectory: PlatformFile?): Boolean {
    val targetPath = selectedDirectory?.path
        ?: resultPath?.let { File(it).parentFile?.absolutePath ?: it }
        ?: return false
    return runCatching {
        Desktop.getDesktop().open(File(targetPath))
    }.isSuccess
}

private class DesktopSampleDownloader : SampleDownloader {
    private val delegate = JvmYoutubeProviderFactory.createDefault()

    override val platformName: String = "Desktop JVM"
    override val isSupported: Boolean = true

    override suspend fun download(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit,
    ): AudioDownloadResult = delegate.downloadAudio(request, emit)

    override suspend fun getTranscript(url: String, includeTimecodes: Boolean): String? {
        return delegate.getTranscript(buildTranscriptRequest(url, includeTimecodes))
    }

    override suspend fun getTranscriptCues(url: String): TranscriptResult? {
        return delegate.getTranscriptCues(buildTranscriptRequest(url, includeTimecodes = false))
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "kt-yt-dlp Compose Sample") {
        SampleApp()
    }
}

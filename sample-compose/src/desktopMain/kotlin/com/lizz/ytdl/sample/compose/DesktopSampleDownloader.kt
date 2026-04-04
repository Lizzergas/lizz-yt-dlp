package com.lizz.ytdl.sample.compose

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadResult
import com.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import java.awt.Desktop
import java.io.File

actual fun createSampleDownloader(): SampleDownloader = DesktopSampleDownloader()
actual fun initializePlatformFileKit() {
    FileKit.init(appId = "com.lizz.ytdl.sample.compose")
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
    private val delegate = JvmNativeYoutubeDownloaderFactory.createDefault()

    override val platformName: String = "Desktop JVM"
    override val isSupported: Boolean = true

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult = delegate.download(request, emit)
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "kt-yt-dlp Compose Sample") {
        SampleApp()
    }
}

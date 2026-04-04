package dev.lizz.ytdl.sample.compose

import android.content.Context
import androidx.activity.ComponentActivity
import dev.lizz.ytdl.core.DownloadEvent
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.core.DownloadResult
import dev.lizz.ytdl.core.TranscriptResult
import dev.lizz.ytdl.core.YoutubeDownloader
import dev.lizz.ytdl.engine.youtube.AndroidNativeYoutubeDownloaderFactory
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.PlatformFile as PlatformFileFactory
import io.github.vinceglb.filekit.manualFileKitCoreInitialization
import io.github.vinceglb.filekit.dialogs.FileKitOpenFileSettings
import io.github.vinceglb.filekit.dialogs.init
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication

private var applicationContextRef: Context? = null

fun createAndroidSampleDownloader(activity: ComponentActivity): SampleDownloader {
    FileKit.manualFileKitCoreInitialization(activity)
    FileKit.init(activity)
    applicationContextRef = activity.applicationContext
    return AndroidSampleDownloader(AndroidNativeYoutubeDownloaderFactory.create(activity.applicationContext))
}

actual fun createSampleDownloader(): SampleDownloader {
    val context = applicationContextRef ?: return UnsupportedAndroidSampleDownloader()
    return AndroidSampleDownloader(AndroidNativeYoutubeDownloaderFactory.create(context))
}

actual fun initializePlatformFileKit() {
    applicationContextRef?.let { FileKit.manualFileKitCoreInitialization(it) }
}

actual suspend fun revealDownloadedDirectory(resultPath: String?, selectedDirectory: PlatformFile?): Boolean {
    val context = applicationContextRef ?: return false

    return selectedDirectory?.let {
        runCatching {
            FileKit.openFileWithDefaultApplication(it)
        }.isSuccess
    } ?: run {
        val path = resultPath ?: return false
        runCatching {
            FileKit.openFileWithDefaultApplication(
                PlatformFileFactory(path),
                FileKitOpenFileSettings(authority = "${context.packageName}.fileprovider"),
            )
        }.isSuccess
    }
}

private class AndroidSampleDownloader(
    private val delegate: YoutubeDownloader,
) : SampleDownloader {
    override val platformName: String = "Android"
    override val isSupported: Boolean = true

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult {
        return delegate.download(request, emit)
    }

    override suspend fun getTranscript(url: String, includeTimecodes: Boolean): String? {
        return delegate.getTranscript(url, includeTimecodes)
    }

    override suspend fun getTranscriptCues(url: String): TranscriptResult? {
        return delegate.getTranscriptCues(url)
    }
}

private class UnsupportedAndroidSampleDownloader : SampleDownloader {
    override val platformName: String = "Android"
    override val isSupported: Boolean = false

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult {
        throw UnsupportedOperationException("Android sample downloader has not been initialized with an Android Context")
    }

    override suspend fun getTranscript(url: String, includeTimecodes: Boolean): String? {
        throw UnsupportedOperationException("Android sample downloader has not been initialized with an Android Context")
    }

    override suspend fun getTranscriptCues(url: String): TranscriptResult? {
        throw UnsupportedOperationException("Android sample downloader has not been initialized with an Android Context")
    }
}

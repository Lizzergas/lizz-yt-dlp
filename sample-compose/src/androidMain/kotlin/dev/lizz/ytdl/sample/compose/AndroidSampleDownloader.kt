package dev.lizz.ytdl.sample.compose

import android.content.Context
import androidx.activity.ComponentActivity
import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.core.AudioDownloadResult
import dev.lizz.ytdl.core.TranscriptResult
import dev.lizz.ytdl.core.MediaClient
import dev.lizz.ytdl.providers.youtube.AndroidYoutubeProviderFactory
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
    return AndroidSampleDownloader(AndroidYoutubeProviderFactory.create(activity.applicationContext))
}

actual fun createSampleDownloader(): SampleDownloader {
    val context = applicationContextRef ?: return UnsupportedAndroidSampleDownloader()
    return AndroidSampleDownloader(AndroidYoutubeProviderFactory.create(context))
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
    private val delegate: MediaClient,
) : SampleDownloader {
    override val platformName: String = "Android"
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

private class UnsupportedAndroidSampleDownloader : SampleDownloader {
    override val platformName: String = "Android"
    override val isSupported: Boolean = false

    override suspend fun download(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit,
    ): AudioDownloadResult {
        throw UnsupportedOperationException("Android sample downloader has not been initialized with an Android Context")
    }

    override suspend fun getTranscript(url: String, includeTimecodes: Boolean): String? {
        throw UnsupportedOperationException("Android sample downloader has not been initialized with an Android Context")
    }

    override suspend fun getTranscriptCues(url: String): TranscriptResult? {
        throw UnsupportedOperationException("Android sample downloader has not been initialized with an Android Context")
    }
}

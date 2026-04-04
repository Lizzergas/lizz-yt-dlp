package dev.lizz.ytdl.engine.youtube

import dev.lizz.ytdl.core.DownloadEvent
import dev.lizz.ytdl.core.TransferSnapshot
import dev.lizz.ytdl.engine.youtube.native.ytdl_transcode_file_to_mp3
import dev.lizz.ytdl.engine.youtube.native.ytdl_transcode_url_to_mp3
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalForeignApi::class)
internal class IosMp3Transcoder {
    suspend fun transcodeFileToMp3(
        inputPath: String,
        outputPath: String,
        durationSeconds: Int?,
        emit: suspend (DownloadEvent) -> Unit,
    ) = withContext(Dispatchers.Default) {
        emit(DownloadEvent.ProgressChanged(TransferSnapshot(label = "Converting audio to mp3", progressPercent = null)))
        val result = ytdl_transcode_file_to_mp3(inputPath, outputPath, 192)
        if (result != 0) {
            throw IllegalStateException("iOS native MP3 transcoder failed with code $result")
        }
        emit(DownloadEvent.ProgressChanged(TransferSnapshot(label = "Converting audio to mp3", progressPercent = 100)))
    }

    suspend fun transcodeUrlToMp3(
        inputUrl: String,
        outputPath: String,
        userAgent: String,
        referer: String,
        origin: String,
        emit: suspend (DownloadEvent) -> Unit,
    ) = withContext(Dispatchers.Default) {
        emit(DownloadEvent.ProgressChanged(TransferSnapshot(label = "Downloading audio from manifest", progressPercent = null)))
        val result = ytdl_transcode_url_to_mp3(inputUrl, outputPath, userAgent, referer, origin, 192)
        if (result != 0) {
            throw IllegalStateException("iOS native URL transcoder failed with code $result")
        }
        emit(DownloadEvent.ProgressChanged(TransferSnapshot(label = "Downloading audio from manifest", progressPercent = 100)))
    }
}

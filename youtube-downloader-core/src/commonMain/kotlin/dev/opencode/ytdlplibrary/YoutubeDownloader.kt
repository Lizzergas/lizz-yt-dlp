package com.lizz.ytdl.core

data class DownloadOptions(
    val outputPath: String? = null,
    val cookiesFromBrowser: String? = null,
    val strictCertificates: Boolean = false,
)

data class DownloadRequest(
    val url: String,
    val options: DownloadOptions = DownloadOptions(),
)

data class DownloadResult(
    val path: String,
    val fileName: String,
    val mimeType: String = "audio/mpeg",
    val metadata: VideoMetadata? = null,
)

enum class DownloadStage(val label: String) {
    PrepareBackend("Prepare Backend"),
    ProbeMetadata("Probe Metadata"),
    DownloadAudio("Download Audio"),
    ConvertToMp3("Convert To MP3"),
    Finalize("Finalize"),
}

data class VideoMetadata(
    val title: String,
    val uploader: String? = null,
    val durationSeconds: Int? = null,
    val durationText: String? = null,
    val sourceUrl: String,
    val availableAudioFormats: Int = 0,
    val bestAudioDescription: String? = null,
)

data class TransferSnapshot(
    val label: String = "Waiting to start",
    val progressPercent: Int? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speedText: String? = null,
    val etaText: String? = null,
)

sealed interface DownloadEvent {
    data class StageChanged(val stage: DownloadStage, val message: String) : DownloadEvent

    data class MetadataResolved(val metadata: VideoMetadata) : DownloadEvent

    data class WorkingFileResolved(val path: String) : DownloadEvent

    data class OutputResolved(val path: String) : DownloadEvent

    data class ProgressChanged(val snapshot: TransferSnapshot) : DownloadEvent

    data class LogEmitted(val message: String) : DownloadEvent

    data class Completed(val outputPath: String) : DownloadEvent

    data class Failed(val message: String) : DownloadEvent
}

interface YoutubeDownloadEngine {
    suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
}

interface YoutubeDownloader {
    suspend fun download(url: String): String

    suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
}

class DefaultYoutubeDownloader(
    private val engine: YoutubeDownloadEngine,
) : YoutubeDownloader {
    override suspend fun download(url: String): String {
        return download(DownloadRequest(url)).path
    }

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult {
        return engine.download(request, emit)
    }
}

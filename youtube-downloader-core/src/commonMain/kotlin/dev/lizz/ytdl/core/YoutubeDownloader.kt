package dev.lizz.ytdl.core

/** Options that control where the downloader writes the final MP3 file. */
public data class DownloadOptions(
    val outputPath: String? = null,
)

/** Input for a single YouTube audio download. */
public data class DownloadRequest(
    val url: String,
    val options: DownloadOptions = DownloadOptions(),
)

/** Final result returned after a successful download. */
public data class DownloadResult(
    val path: String,
    val fileName: String,
    val mimeType: String = "audio/mpeg",
    val metadata: VideoMetadata? = null,
)

/** High-level phases emitted while a download is running. */
public enum class DownloadStage(public val label: String) {
    PrepareBackend("Prepare Backend"),
    ProbeMetadata("Probe Metadata"),
    DownloadAudio("Download Audio"),
    ConvertToMp3("Convert To MP3"),
    Finalize("Finalize"),
}

/** Metadata resolved from the selected YouTube video. */
public data class VideoMetadata(
    val title: String,
    val uploader: String? = null,
    val durationSeconds: Int? = null,
    val durationText: String? = null,
    val sourceUrl: String,
    val availableAudioFormats: Int = 0,
    val bestAudioDescription: String? = null,
)

/** Transfer progress emitted during download and transcode work. */
public data class TransferSnapshot(
    val label: String = "Waiting to start",
    val progressPercent: Int? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speedText: String? = null,
    val etaText: String? = null,
)

/** Stream of structured events produced while a download is running. */
public sealed interface DownloadEvent {
    /** The downloader entered a new high-level stage. */
    public data class StageChanged(val stage: DownloadStage, val message: String) : DownloadEvent

    /** Metadata was resolved before the media transfer started. */
    public data class MetadataResolved(val metadata: VideoMetadata) : DownloadEvent

    /** A temporary working file path was created for the current transfer. */
    public data class WorkingFileResolved(val path: String) : DownloadEvent

    /** The final MP3 output path was selected. */
    public data class OutputResolved(val path: String) : DownloadEvent

    /** Download or transcode progress changed. */
    public data class ProgressChanged(val snapshot: TransferSnapshot) : DownloadEvent

    /** Diagnostic output emitted by the engine. */
    public data class LogEmitted(val message: String) : DownloadEvent

    /** The final MP3 file is ready to use. */
    public data class Completed(val outputPath: String) : DownloadEvent

    /** The download failed with a terminal error. */
    public data class Failed(val message: String) : DownloadEvent
}

/** Platform runtime contract used by concrete downloader implementations. */
public interface YoutubeDownloadEngine {
    /** Downloads a single YouTube URL and emits progress updates. */
    public suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
}

/** Public downloader API exposed to application code. */
public interface YoutubeDownloader {
    /** Downloads the given URL and returns the output path. */
    public suspend fun download(url: String): String

    /** Downloads a single YouTube URL and emits structured progress events. */
    public suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
}

/** Default implementation that delegates all work to a platform engine. */
public class DefaultYoutubeDownloader(
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

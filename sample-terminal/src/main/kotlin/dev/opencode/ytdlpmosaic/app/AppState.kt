package com.lizz.ytdl.sample.terminal.app

import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadStage
import com.lizz.ytdl.core.TransferSnapshot
import com.lizz.ytdl.core.VideoMetadata

data class AppState(
    val request: DownloadRequest? = null,
    val activeStage: DownloadStage = DownloadStage.PrepareBackend,
    val statusMessage: String = "Waiting to start",
    val metadata: VideoMetadata? = null,
    val workingFilePath: String? = null,
    val outputPath: String? = null,
    val transfer: TransferSnapshot = TransferSnapshot(),
    val logs: List<String> = emptyList(),
    val finished: Boolean = false,
    val failureMessage: String? = null,
    val backendSummary: String = "KMP facade + native JVM engine",
)

fun AppState.withLog(message: String): AppState {
    val nextLogs = (logs + message).takeLast(200)
    return copy(logs = nextLogs)
}

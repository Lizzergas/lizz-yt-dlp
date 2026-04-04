package com.lizz.ytdl.sample.terminal.app

import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadStage
import com.lizz.ytdl.core.TransferSnapshot
import com.lizz.ytdl.core.YoutubeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class AppViewModel(
    private val downloader: YoutubeDownloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(AppState())
    val uiStateFlow: StateFlow<AppState> = _uiState.asStateFlow()

    private var started = false

    fun start(request: DownloadRequest) {
        if (started) return
        started = true

        _uiState.value = AppState(
            request = request,
            activeStage = DownloadStage.PrepareBackend,
            statusMessage = "Starting download pipeline",
        ).withLog("Starting YouTube mp3 download for ${request.url}")

        scope.launch {
            try {
                downloader.download(request) { event ->
                    handleEvent(event)
                }
            } catch (_: Exception) {
                // Failure state is already emitted by the engine.
            }
        }
    }

    fun quit() {
        exitProcess(0)
    }

    private fun handleEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.StageChanged -> {
                _uiState.value = _uiState.value
                    .copy(activeStage = event.stage, statusMessage = event.message)
                    .withLog("${event.stage.label}: ${event.message}")
            }

            is DownloadEvent.MetadataResolved -> {
                _uiState.value = _uiState.value
                    .copy(metadata = event.metadata)
                    .withLog("Resolved metadata for ${event.metadata.title}")
            }

            is DownloadEvent.WorkingFileResolved -> {
                _uiState.value = _uiState.value
                    .copy(workingFilePath = event.path)
                    .withLog("Downloaded working audio file: ${event.path}")
            }

            is DownloadEvent.OutputResolved -> {
                _uiState.value = _uiState.value
                    .copy(outputPath = event.path)
                    .withLog("Planned final mp3 path: ${event.path}")
            }

            is DownloadEvent.ProgressChanged -> {
                _uiState.value = _uiState.value.copy(transfer = event.snapshot)
            }

            is DownloadEvent.LogEmitted -> {
                _uiState.value = _uiState.value.withLog(event.message)
            }

            is DownloadEvent.Completed -> {
                _uiState.value = _uiState.value
                    .copy(
                        finished = true,
                        outputPath = event.outputPath,
                        activeStage = DownloadStage.Finalize,
                        statusMessage = "Saved mp3 to ${event.outputPath}",
                        transfer = TransferSnapshot(
                            label = "Complete",
                            progressPercent = 100,
                            downloadedBytes = _uiState.value.transfer.downloadedBytes,
                            totalBytes = _uiState.value.transfer.totalBytes,
                        ),
                    )
                    .withLog("Completed successfully: ${event.outputPath}")
            }

            is DownloadEvent.Failed -> {
                _uiState.value = _uiState.value
                    .copy(failureMessage = event.message, statusMessage = event.message)
                    .withLog("Failed: ${event.message}")
            }
        }
    }
}

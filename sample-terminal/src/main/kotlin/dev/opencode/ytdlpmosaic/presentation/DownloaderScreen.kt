package com.lizz.ytdl.sample.terminal.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadStage
import com.lizz.ytdl.sample.terminal.app.AppState
import com.lizz.ytdl.sample.terminal.app.AppViewModel
import com.lizz.ytdl.sample.terminal.presentation.components.AsciiPanel
import com.lizz.ytdl.sample.terminal.presentation.components.StatusBar

@Composable
fun DownloaderScreen(
    viewModel: AppViewModel,
    request: DownloadRequest,
) {
    val state by viewModel.uiStateFlow.collectAsState()
    val terminalSize = LocalTerminalState.current.size
    val panelWidth = terminalSize.columns
        .takeIf { it > 8 }
        ?.minus(2)
        ?.coerceAtMost(100)
        ?.coerceAtLeast(40)
        ?: 72
    val logRows = terminalSize.rows
        .takeIf { it > 12 }
        ?.let { (it - 26).coerceAtLeast(5) }
        ?: 8

    LaunchedEffect(request) {
        viewModel.start(request)
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 1)
            .onKeyEvent {
                when (it) {
                    KeyEvent("q"),
                    KeyEvent("Q"),
                    KeyEvent("Escape"),
                    -> {
                        viewModel.quit()
                        true
                    }

                    else -> false
                }
            },
    ) {
        Text("kt-ytdl Terminal Sample", color = Color(110, 190, 255))
        Text(
            "Sample terminal app using the KMP facade and native JVM YouTube mp3 engine",
            color = Color(170, 170, 170),
        )
        Spacer(Modifier.height(1))
        AsciiPanel("Request", panelWidth, requestLines(state), bodyRows = 6)
        Spacer(Modifier.height(1))
        AsciiPanel("Pipeline", panelWidth, pipelineLines(state), bodyRows = DownloadStage.entries.size)
        Spacer(Modifier.height(1))
        AsciiPanel("Metadata", panelWidth, metadataLines(state), bodyRows = 8)
        Spacer(Modifier.height(1))
        AsciiPanel("Transfer", panelWidth, transferLines(state, panelWidth), bodyRows = 7)
        Spacer(Modifier.height(1))
        AsciiPanel("Files", panelWidth, fileLines(state), bodyRows = 5)
        Spacer(Modifier.height(1))
        AsciiPanel("Log", panelWidth, logLines(state), bodyRows = logRows)
        Spacer(Modifier.height(1))
        StatusBar(text = statusBarText(state))
    }
}

private fun requestLines(state: AppState): List<String> {
    val request = state.request
    return listOf(
        "URL: ${request?.url ?: "pending"}",
        "Output target: ${request?.options?.outputPath ?: "current directory"}",
        "Certificates: ${if (request?.options?.strictCertificates == true) "strict" else "relaxed for this environment"}",
        "Backend: ${state.backendSummary}",
        "Engine: native Kotlin YouTube downloader",
        "Press q or Esc to quit",
    )
}

private fun pipelineLines(state: AppState): List<String> {
    return DownloadStage.entries.map { stage ->
        val icon = when {
            state.failureMessage != null && stage == state.activeStage -> "[!]"
            state.finished || stage.ordinal < state.activeStage.ordinal -> "[x]"
            stage == state.activeStage -> "[>]"
            else -> "[ ]"
        }
        "$icon ${stage.label}"
    }
}

private fun metadataLines(state: AppState): List<String> {
    val metadata = state.metadata
    return listOf(
        "Title: ${metadata?.title ?: "pending"}",
        "Uploader: ${metadata?.uploader ?: "pending"}",
        "Duration: ${metadata?.durationText ?: "pending"}",
        "Audio formats: ${metadata?.availableAudioFormats ?: 0}",
        "Best audio guess: ${metadata?.bestAudioDescription ?: "pending"}",
        "Current stage: ${state.activeStage.label}",
        "Status: ${state.failureMessage ?: if (state.finished) "complete" else "running"}",
        "Source: ${metadata?.sourceUrl ?: state.request?.url ?: "pending"}",
    )
}

private fun transferLines(state: AppState, width: Int): List<String> {
    val snapshot = state.transfer
    val progressPercent = snapshot.progressPercent ?: 0
    val barWidth = (width - 18).coerceAtLeast(10)
    val filled = (progressPercent * barWidth / 100).coerceAtLeast(0)
    val bar = "[${"#".repeat(filled)}${"-".repeat((barWidth - filled).coerceAtLeast(0))}]"

    return listOf(
        "Phase: ${snapshot.label}",
        "Progress: $bar ${snapshot.progressPercent?.let { "$it%" } ?: "n/a"}",
        "Bytes: ${snapshot.downloadedBytes?.let(::formatBytes) ?: "n/a"} / ${snapshot.totalBytes?.let(::formatBytes) ?: "unknown"}",
        "Speed: ${snapshot.speedText ?: "n/a"}",
        "ETA: ${snapshot.etaText ?: "n/a"}",
        "State: ${state.failureMessage ?: state.statusMessage}",
        if (state.finished) "Result: finished" else "Result: in progress",
    )
}

private fun fileLines(state: AppState): List<String> {
    return listOf(
        "Working audio: ${state.workingFilePath ?: "pending"}",
        "Final mp3: ${state.outputPath ?: "pending"}",
        "Failure: ${state.failureMessage ?: "none"}",
        "Finished: ${if (state.finished) "yes" else "no"}",
        "Stage message: ${state.statusMessage}",
    )
}

private fun logLines(state: AppState): List<String> = state.logs.takeLast(50)

private fun statusBarText(state: AppState): String {
    return buildString {
        append("q quit")
        append(" | stage: ${state.activeStage.label}")
        append(" | backend: ${state.backendSummary}")
        state.outputPath?.let { append(" | output: $it") }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

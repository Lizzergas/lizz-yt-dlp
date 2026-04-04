package com.lizz.ytdl.sample.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lizz.ytdl.core.DownloadEvent
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.PlatformFile as PlatformFileFactory
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch

@Composable
fun SampleApp(
    downloader: SampleDownloader = createSampleDownloader(),
) {
    var url by remember { mutableStateOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ") }
    var outputPath by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready") }
    var resultPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var usedFallback by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var selectedDirectory by remember { mutableStateOf<PlatformFile?>(null) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        initializePlatformFileKit()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("kt-yt-dlp Compose Sample", style = MaterialTheme.typography.headlineSmall)
                Text("Platform: ${downloader.platformName}")
                Text(
                    if (downloader.isSupported) {
                        "Downloader engine available on this platform"
                    } else {
                        "Platform wiring is ready, but the native downloader is not implemented here yet"
                    },
                    color = if (downloader.isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube URL") },
                )

                OutlinedTextField(
                    value = selectedDirectory?.path ?: outputPath,
                    onValueChange = { outputPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Selected save directory") },
                    placeholder = { Text("Use FileKit to choose a save location or leave blank for app default") },
                    readOnly = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !downloading,
                        onClick = {
                            scope.launch {
                                selectedDirectory = FileKit.openDirectoryPicker()
                            }
                        },
                    ) {
                        Text("Choose Save Directory")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !downloading && selectedDirectory != null,
                        colors = ButtonDefaults.outlinedButtonColors(),
                        onClick = {
                            selectedDirectory = null
                            outputPath = ""
                        },
                    ) {
                        Text("Use App Default")
                    }

                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = downloader.isSupported && !downloading && url.isNotBlank(),
                        onClick = {
                            downloading = true
                            status = "Starting download"
                            resultPath = null
                            error = null
                            usedFallback = false
                            logs.clear()
                            scope.launch {
                                runCatching {
                                    val request = buildDownloadRequest(url, if (selectedDirectory == null) outputPath else "")
                                    val result = downloader.download(request) { event ->
                                        when (event) {
                                            is DownloadEvent.StageChanged -> {
                                                status = event.message
                                                logs += "${event.stage.label}: ${event.message}"
                                            }

                                            is DownloadEvent.LogEmitted -> {
                                                if (event.message.contains("Falling back to ")) {
                                                    usedFallback = true
                                                }
                                                logs += event.message
                                            }
                                            is DownloadEvent.ProgressChanged -> {
                                                status = buildString {
                                                    append(event.snapshot.label)
                                                    event.snapshot.progressPercent?.let { append(" ($it%)") }
                                                }
                                            }

                                            is DownloadEvent.OutputResolved -> resultPath = event.path
                                            is DownloadEvent.Completed -> {
                                                status = if (usedFallback) "Download complete via fallback" else "Download complete"
                                                resultPath = event.outputPath
                                            }

                                            is DownloadEvent.Failed -> {
                                                error = event.message
                                                status = "Download failed"
                                            }

                                            else -> Unit
                                        }
                                    }
                                    if (selectedDirectory != null) {
                                        val sourceFile = PlatformFileFactory(result.path)
                                        val destination = PlatformFileFactory(selectedDirectory!!, result.fileName)
                                        FileKitIo.copy(sourceFile, destination)
                                        sourceFile.delete(mustExist = false)
                                        resultPath = destination.path
                                        logs += "Moved downloaded file into ${selectedDirectory!!.path}"
                                    }
                                }.onFailure {
                                    error = it.message ?: it.toString()
                                    status = "Download failed"
                                }
                                downloading = false
                            }
                        },
                    ) {
                        Text(if (downloading) "Downloading..." else "Download MP3")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !downloading && (resultPath != null || selectedDirectory != null),
                        onClick = {
                            scope.launch {
                                if (!revealDownloadedDirectory(resultPath, selectedDirectory)) {
                                    error = "Could not open the save location on this platform"
                                }
                            }
                        },
                    ) {
                        Text("Open Directory")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = logs.isNotEmpty(),
                        onClick = {
                            clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                            status = "Event log copied to clipboard"
                        },
                    ) {
                        Text("Copy Logs")
                    }
                }

                StatusRow("Status", status)
                StatusRow("Result", resultPath ?: "pending")
                StatusRow("Error", error ?: "none")

                Spacer(Modifier.height(8.dp))
                Text("Event log", fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    logs.forEach { line ->
                        Text(
                            line,
                            color = when {
                                line.contains("Falling back to ") -> MaterialTheme.colorScheme.secondary
                                line.contains("was rejected, trying fallback") || line.contains("unavailable:") -> Color(0xFF8A6D1D)
                                else -> Color.Unspecified
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.width(72.dp), fontWeight = FontWeight.Bold)
        Text(value)
    }
}

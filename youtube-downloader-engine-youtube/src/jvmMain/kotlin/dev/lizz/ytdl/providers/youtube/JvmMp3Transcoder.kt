package dev.lizz.ytdl.providers.youtube

import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.TransferSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JvmMp3Transcoder {
    suspend fun transcodeFileToMp3(
        inputFile: Path,
        outputFile: Path,
        durationSeconds: Int?,
        emit: suspend (MediaEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        outputFile.toAbsolutePath().parent?.let(Files::createDirectories)
        runFfmpeg(
            command = listOf(
                "ffmpeg",
                "-y",
                "-i",
                inputFile.toString(),
                "-vn",
                "-map_metadata",
                "0",
                "-codec:a",
                "libmp3lame",
                "-q:a",
                "0",
                "-progress",
                "pipe:1",
                "-nostats",
                outputFile.toString(),
            ),
            label = "Converting audio to mp3",
            durationSeconds = durationSeconds,
            failureMessage = "ffmpeg failed",
            emit = emit,
        )
    }

    suspend fun transcodeManifestToMp3(
        manifestUrl: String,
        outputFile: Path,
        refererUrl: String,
        userAgent: String,
        durationSeconds: Int?,
        emit: suspend (MediaEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        outputFile.toAbsolutePath().parent?.let(Files::createDirectories)
        val headers = listOf(
            "Referer: $refererUrl",
            "Origin: https://www.youtube.com",
        ).joinToString("\r\n", postfix = "\r\n")

        runFfmpeg(
            command = listOf(
                "ffmpeg",
                "-y",
                "-user_agent",
                userAgent,
                "-headers",
                headers,
                "-i",
                manifestUrl,
                "-vn",
                "-map_metadata",
                "0",
                "-codec:a",
                "libmp3lame",
                "-q:a",
                "0",
                "-progress",
                "pipe:1",
                "-nostats",
                outputFile.toString(),
            ),
            label = "Downloading audio from manifest",
            durationSeconds = durationSeconds,
            failureMessage = "ffmpeg manifest download failed",
            emit = emit,
        )
    }

    private suspend fun runFfmpeg(
        command: List<String>,
        label: String,
        durationSeconds: Int?,
        failureMessage: String,
        emit: suspend (MediaEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()

        var outTimeUs: Long? = null
        var speed: String? = null
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("out_time_us=") -> outTimeUs = line.substringAfter('=').toLongOrNull()
                    line.startsWith("speed=") -> speed = line.substringAfter('=').takeUnless { it == "N/A" }
                    line.startsWith("progress=") -> {
                        val status = line.substringAfter('=')
                        val percent = when {
                            status == "end" -> 100
                            durationSeconds != null && durationSeconds > 0 && outTimeUs != null -> {
                                ((outTimeUs!!.toDouble() / 1_000_000.0 / durationSeconds) * 100)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                            }

                            else -> null
                        }
                        emit(
                            MediaEvent.ProgressChanged(
                                TransferSnapshot(
                                    label = label,
                                    progressPercent = percent,
                                    speedText = speed,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        val exit = process.waitFor()
        if (exit != 0) throw IllegalStateException("$failureMessage with exit code $exit")
    }
}

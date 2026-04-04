@file:JvmName("Main")

package com.lizz.ytdl.sample.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.DownloadOptions
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory
import kotlin.jvm.JvmName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class YtdlCli : SuspendingCliktCommand(
    name = "ytdl",
) {
    private val url by argument(help = "YouTube URL to download")
    private val output by option("-o", "--output", help = "Output directory or mp3 file path")
    private val strictCerts by option("--strict-certs", help = "Enable strict certificate validation").flag(default = false)
    private val verbose by option("-v", "--verbose", help = "Print backend event messages").flag(default = false)
    private val jsonProgress by option("--json-progress", help = "Print events as JSON lines").flag(default = false)

    init {
        versionOption("0.1.0")
    }

    override fun help(context: com.github.ajalt.clikt.core.Context): String {
        return "Download a YouTube URL to mp3 using the KMP facade and JVM on-device engine"
    }

    override suspend fun run() {
        val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()
        val request = DownloadRequest(
            url = url,
            options = DownloadOptions(
                outputPath = output,
                strictCertificates = strictCerts,
            ),
        )

        val result = downloader.download(request) { event ->
            when {
                jsonProgress -> echo(eventAsJson(event))
                verbose -> echo(eventAsText(event), err = event is DownloadEvent.Failed)
                event is DownloadEvent.Failed -> echo(eventAsText(event), err = true)
            }
        }

        echo(result.path)
    }
}

suspend fun main(args: Array<String>) = YtdlCli().main(args)

private fun eventAsText(event: DownloadEvent): String {
    return when (event) {
        is DownloadEvent.StageChanged -> "stage=${event.stage.label} message=${event.message}"
        is DownloadEvent.MetadataResolved -> "metadata title=${event.metadata.title} uploader=${event.metadata.uploader ?: "unknown"}"
        is DownloadEvent.WorkingFileResolved -> "working-file path=${event.path}"
        is DownloadEvent.OutputResolved -> "output-file path=${event.path}"
        is DownloadEvent.ProgressChanged -> "progress phase=${event.snapshot.label} percent=${event.snapshot.progressPercent ?: -1} bytes=${event.snapshot.downloadedBytes ?: -1}/${event.snapshot.totalBytes ?: -1} speed=${event.snapshot.speedText ?: "n/a"} eta=${event.snapshot.etaText ?: "n/a"}"
        is DownloadEvent.LogEmitted -> "log ${event.message}"
        is DownloadEvent.Completed -> "completed path=${event.outputPath}"
        is DownloadEvent.Failed -> "failed message=${event.message}"
    }
}

private fun eventAsJson(event: DownloadEvent): String {
    return buildJsonObject {
        when (event) {
            is DownloadEvent.StageChanged -> {
                put("type", "stage")
                put("stage", event.stage.label)
                put("message", event.message)
            }

            is DownloadEvent.MetadataResolved -> {
                put("type", "metadata")
                put("title", event.metadata.title)
                put("uploader", event.metadata.uploader)
                put("durationSeconds", event.metadata.durationSeconds)
                put("audioFormats", event.metadata.availableAudioFormats)
            }

            is DownloadEvent.WorkingFileResolved -> {
                put("type", "workingFile")
                put("path", event.path)
            }

            is DownloadEvent.OutputResolved -> {
                put("type", "outputFile")
                put("path", event.path)
            }

            is DownloadEvent.ProgressChanged -> {
                put("type", "progress")
                put("label", event.snapshot.label)
                put("progressPercent", event.snapshot.progressPercent)
                put("downloadedBytes", event.snapshot.downloadedBytes)
                put("totalBytes", event.snapshot.totalBytes)
                put("speedText", event.snapshot.speedText)
                put("etaText", event.snapshot.etaText)
            }

            is DownloadEvent.LogEmitted -> {
                put("type", "log")
                put("message", event.message)
            }

            is DownloadEvent.Completed -> {
                put("type", "completed")
                put("path", event.outputPath)
            }

            is DownloadEvent.Failed -> {
                put("type", "failed")
                put("message", event.message)
            }
        }
    }.toString()
}

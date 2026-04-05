package dev.lizz.ytdl.providers.youtube

import dev.lizz.ytdl.core.AudioDownloadOptions
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.core.AudioDownloadResult
import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.MediaProvider
import dev.lizz.ytdl.core.MediaStage
import dev.lizz.ytdl.core.ProviderId
import dev.lizz.ytdl.core.TranscriptRequest
import dev.lizz.ytdl.core.TranscriptResult
import dev.lizz.ytdl.core.TransferSnapshot
import dev.lizz.ytdl.providers.youtube.probe.YoutubeAudioPlanner
import dev.lizz.ytdl.providers.youtube.probe.YoutubeProbeService
import dev.lizz.ytdl.providers.youtube.probe.YoutubeProbeTransport
import dev.lizz.ytdl.providers.youtube.probe.userAgentForSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

internal class JvmYoutubeProvider(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val commons: YoutubeExtractorCommons = YoutubeExtractorCommons(),
    private val transcoder: JvmMp3Transcoder = JvmMp3Transcoder(),
) : MediaProvider {
    override val id: ProviderId = YoutubeProviderSupport.providerId
    private val probeService = YoutubeProbeService(commons = commons)
    private val probeTransport = object : YoutubeProbeTransport {
        override suspend fun getText(url: String, headers: Map<String, String>): String =
            this@JvmYoutubeProvider.getText(url, headers)

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: JsonObject,
        ): JsonObject = this@JvmYoutubeProvider.postJson(url, headers, body)
    }

    override fun canHandle(locator: String): Boolean = YoutubeProviderSupport.canHandle(locator)

    override suspend fun downloadAudio(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit,
    ): AudioDownloadResult = withContext(Dispatchers.IO) {
        val probe = probeService.probe(request.url, probeTransport, emit = emit)
        val resolved = probe.resolvedMedia
        val watchData = probe.watchData
        emit(MediaEvent.MetadataResolved(resolved.metadata))

        val stagingDirectory = Files.createTempDirectory("kt-ytdlp-native-")
        val audioPlan = YoutubeAudioPlanner.plan(
            resolved,
            manifestPriority = listOf("dash", "hls"),
            preferredFormatPicker = commons::pickBestAudioFormat
        )
        audioPlan.preferredDirectFormat?.let { emit(MediaEvent.LogEmitted("Native engine selected ${it.describe()}")) }

        val outputFile = resolveOutputPath(request.options, resolved.metadata.title)
        emit(MediaEvent.OutputResolved(outputFile.toString()))

        val directDownloadSucceeded = if (audioPlan.preferredDirectFormat == null) {
            false
        } else {
            val workingFile =
                stagingDirectory.resolve("audio.${audioPlan.preferredDirectFormat.ext}")
            emit(
                MediaEvent.StageChanged(
                    MediaStage.DownloadAudio,
                    "Downloading direct audio stream resolved by native engine"
                )
            )
            runCatching {
                val downloadedFormat = downloadFirstAvailableFormat(
                    audioPlan.directFormats,
                    workingFile,
                    watchData.canonicalUrl,
                    emit
                )
                emit(MediaEvent.WorkingFileResolved(workingFile.toString()))
                emit(MediaEvent.LogEmitted("Downloaded using ${downloadedFormat.describe()}"))

                emit(
                    MediaEvent.StageChanged(
                        MediaStage.ConvertOutput,
                        "Transcoding the downloaded audio stream into mp3"
                    )
                )
                transcoder.transcodeFileToMp3(
                    inputFile = workingFile,
                    outputFile = outputFile,
                    durationSeconds = resolved.metadata.durationSeconds,
                    emit = emit,
                )
                Files.deleteIfExists(workingFile)
                true
            }.getOrElse { error ->
                emit(MediaEvent.LogEmitted("Direct media URLs were not usable: ${error.message}"))
                Files.deleteIfExists(workingFile)
                false
            }
        }

        if (!directDownloadSucceeded) {
            val manifest = audioPlan.manifestFallbacks.firstOrNull()
                ?: throw IllegalStateException("Direct download failed and no HLS manifest fallback was available")
            emit(MediaEvent.LogEmitted("Falling back to ${manifest.kind.uppercase()} manifest from ${manifest.source}"))
            emit(
                MediaEvent.StageChanged(
                    MediaStage.ConvertOutput,
                    "Downloading and transcoding audio from ${manifest.kind.uppercase()} manifest"
                )
            )
            transcoder.transcodeManifestToMp3(
                manifestUrl = manifest.url,
                outputFile = outputFile,
                refererUrl = watchData.canonicalUrl,
                userAgent = userAgentForSource(manifest.source),
                durationSeconds = resolved.metadata.durationSeconds,
                emit = emit,
            )
        }

        emit(
            MediaEvent.StageChanged(
                MediaStage.Finalize,
                "Cleaning temporary files and finalizing output"
            )
        )
        Files.deleteIfExists(stagingDirectory)
        emit(MediaEvent.Completed(outputFile.toString()))

        AudioDownloadResult(
            path = outputFile.toString(),
            fileName = outputFile.fileName.toString(),
            metadata = resolved.metadata,
            provider = id,
        )
    }

    override suspend fun getTranscript(request: TranscriptRequest): String? {
        val transcript = getTranscriptCues(request) ?: return null
        return YoutubeTranscriptFormatter.format(transcript, request.includeTimecodes)
    }

    override suspend fun getTranscriptCues(request: TranscriptRequest): TranscriptResult? =
        withContext(Dispatchers.IO) {
            val probe = probeService.probe(request.url, probeTransport, emit = {})
            probeService.getTranscriptCues(probe, request, probeTransport)
        }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): JsonObject {
        val requestBuilder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        headers.forEach(requestBuilder::header)
        val response = sendBytes(requestBuilder.build())
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
        return kotlinx.serialization.json.Json.parseToJsonElement(
            decodeBody(response).toString(
                StandardCharsets.UTF_8
            )
        ) as JsonObject
    }

    private suspend fun downloadFirstAvailableFormat(
        formats: List<NativeAudioFormat>,
        output: Path,
        refererUrl: String,
        emit: suspend (MediaEvent) -> Unit,
    ): NativeAudioFormat {
        var lastError: Exception? = null
        for (format in formats) {
            runCatching {
                Files.deleteIfExists(output)
                downloadFile(format, output, refererUrl, emit)
                format
            }.onSuccess { return it }
                .onFailure { error ->
                    lastError = error as? Exception ?: IllegalStateException(
                        error.message ?: error.toString()
                    )
                    emit(MediaEvent.LogEmitted("Format ${format.describe()} was rejected, trying fallback: ${error.message}"))
                }
        }
        throw lastError ?: IllegalStateException("No audio format could be downloaded")
    }

    private suspend fun downloadFile(
        format: NativeAudioFormat,
        output: Path,
        refererUrl: String,
        emit: suspend (MediaEvent) -> Unit,
    ) {
        val requestBuilder = HttpRequest.newBuilder(URI(format.url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgentForSource(format.source))
            .header("Accept-Encoding", "identity")
            .header("Accept", "*/*")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", refererUrl)
            .header("Range", "bytes=0-")
            .GET()

        if (format.source == "ios-player-api") {
            requestBuilder.header("X-YouTube-Client-Name", "5")
            requestBuilder.header("X-YouTube-Client-Version", "21.02.3")
        }

        val request = requestBuilder.build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw IllegalStateException("Native direct download returned HTTP ${response.statusCode()} for ${format.describe()}")
        }

        val totalBytes =
            response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf { it >= 0L }
        var written = 0L

        response.body().use { input ->
            Files.newOutputStream(output).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    val percent = totalBytes?.let {
                        ((written.toDouble() / it) * 100).roundToInt().coerceIn(0, 100)
                    }
                    emit(
                        MediaEvent.ProgressChanged(
                            TransferSnapshot(
                                label = "Downloading audio stream",
                                progressPercent = percent,
                                downloadedBytes = written,
                                totalBytes = totalBytes,
                                speedText = format.describe(),
                            )
                        )
                    )
                }
            }
        }
    }

    private fun getText(url: String, headers: Map<String, String>): String {
        val requestBuilder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET()
        headers.forEach(requestBuilder::header)
        val response = sendBytes(requestBuilder.build())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Watch page returned HTTP ${response.statusCode()}")
        }
        val bytes = decodeBody(response)
        val charset =
            charsetFromContentType(response.headers().firstValue("Content-Type").orElse(null))
        return bytes.toString(charset)
    }

    private fun sendBytes(request: HttpRequest): HttpResponse<ByteArray> {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun decodeBody(response: HttpResponse<ByteArray>): ByteArray {
        val encoding = response.headers().firstValue("Content-Encoding").orElse("").lowercase()
        return when (encoding) {
            "gzip" -> GZIPInputStream(ByteArrayInputStream(response.body())).readBytes()
            "deflate" -> InflaterInputStream(ByteArrayInputStream(response.body())).readBytes()
            else -> response.body()
        }
    }

    private fun charsetFromContentType(contentType: String?): Charset {
        val charsetName = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
        return charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.UTF_8
    }

    private fun resolveOutputPath(options: AudioDownloadOptions, title: String): Path {
        val safeTitle =
            title.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim()
                .take(120).ifBlank { "youtube-audio" }
        val defaultFileName = "$safeTitle.mp3"
        val outputTarget = options.outputPath

        if (outputTarget.isNullOrBlank()) return uniquePath(
            Path.of(defaultFileName).toAbsolutePath()
        )

        val rawPath = Path.of(outputTarget)
        val looksLikeDirectory =
            outputTarget.endsWith("/") || outputTarget.endsWith("\\") || !rawPath.fileName.toString()
                .contains('.')
        val resolved = when {
            Files.exists(rawPath) && Files.isDirectory(rawPath) -> rawPath.resolve(defaultFileName)
            looksLikeDirectory -> rawPath.resolve(defaultFileName)
            rawPath.fileName.toString().endsWith(".mp3", ignoreCase = true) -> rawPath
            else -> rawPath.resolveSibling(rawPath.fileName.toString() + ".mp3")
        }

        val absoluteResolved = resolved.toAbsolutePath()
        absoluteResolved.parent?.let(Files::createDirectories)
        return uniquePath(absoluteResolved)
    }

    private fun uniquePath(path: Path): Path {
        if (!Files.exists(path)) return path
        val fileName = path.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val stem = if (dotIndex >= 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex >= 0) fileName.substring(dotIndex) else ""
        var attempt = 1
        while (true) {
            val candidate = path.resolveSibling("$stem ($attempt)$ext")
            if (!Files.exists(candidate)) return candidate
            attempt++
        }
    }

}

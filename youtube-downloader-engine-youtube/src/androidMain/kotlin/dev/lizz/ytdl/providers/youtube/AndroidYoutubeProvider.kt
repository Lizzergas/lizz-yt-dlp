package dev.lizz.ytdl.providers.youtube

import android.content.Context
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
import dev.lizz.ytdl.providers.youtube.hls.HlsAudioSegments
import dev.lizz.ytdl.providers.youtube.net.YoutubeHttpResult
import dev.lizz.ytdl.providers.youtube.perf.DOWNLOAD_BUFFER_SIZE_BYTES
import dev.lizz.ytdl.providers.youtube.perf.HLS_DOWNLOAD_PARALLELISM
import dev.lizz.ytdl.providers.youtube.perf.ProgressStepTracker
import dev.lizz.ytdl.providers.youtube.perf.parallelMapBatched
import dev.lizz.ytdl.providers.youtube.probe.YoutubeAudioPlanner
import dev.lizz.ytdl.providers.youtube.probe.YoutubeProbeService
import dev.lizz.ytdl.providers.youtube.probe.YoutubeProbeTransport
import dev.lizz.ytdl.providers.youtube.probe.userAgentForSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

internal class AndroidYoutubeProvider(
    private val context: Context,
    private val commons: YoutubeExtractorCommons = YoutubeExtractorCommons(),
    private val transcoder: AndroidMp3Transcoder = AndroidMp3Transcoder(),
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 30_000
        }
        followRedirects = true
    },
) : MediaProvider {
    override val id: ProviderId = YoutubeProviderSupport.providerId
    private val probeService = YoutubeProbeService(commons = commons)
    private val probeTransport = object : YoutubeProbeTransport {
        override suspend fun getText(url: String, headers: Map<String, String>): String =
            this@AndroidYoutubeProvider.getText(url, headers)

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: JsonObject,
        ): JsonObject = this@AndroidYoutubeProvider.postJson(url, headers, body)
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

        val stagingDirectory = File(context.cacheDir, "kt-ytdlp-native").apply { mkdirs() }
        val audioPlan = YoutubeAudioPlanner.plan(
            resolved,
            manifestPriority = listOf("hls"),
            preferredFormatPicker = commons::pickBestAudioFormat
        )
        audioPlan.preferredDirectFormat?.let { emit(MediaEvent.LogEmitted("Native engine selected ${it.describe()}")) }

        val outputFile = resolveOutputFile(request.options, resolved.metadata.title)
        emit(MediaEvent.OutputResolved(outputFile.absolutePath))

        val directDownloadSucceeded = if (audioPlan.preferredDirectFormat == null) {
            false
        } else {
            val workingFile = File(stagingDirectory, "audio.${audioPlan.preferredDirectFormat.ext}")
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
                emit(MediaEvent.WorkingFileResolved(workingFile.absolutePath))
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
                workingFile.delete()
                true
            }.getOrElse { error ->
                emit(MediaEvent.LogEmitted("Direct media URLs were not usable: ${error.message}"))
                workingFile.delete()
                false
            }
        }

        if (!directDownloadSucceeded) {
            val manifest = audioPlan.manifestFallbacks.firstOrNull()
                ?: throw IllegalStateException("Direct download failed and no HLS manifest fallback was available")
            emit(MediaEvent.LogEmitted("Falling back to ${manifest.kind.uppercase()} manifest from ${manifest.source}"))
            val manifestHeaders = mapOf(
                "Referer" to watchData.canonicalUrl,
                "Origin" to "https://www.youtube.com",
                "User-Agent" to userAgentForSource(manifest.source),
            )
            val playableManifestUrl =
                selectAndroidPlayableManifestUrl(manifest.url, manifestHeaders, emit)
            emit(MediaEvent.LogEmitted("Android manifest URL: $playableManifestUrl"))
            val hlsWorkingFile = downloadHlsAudioToLocalFile(
                manifestUrl = playableManifestUrl,
                headers = manifestHeaders,
                stagingDirectory = stagingDirectory,
                emit = emit,
            )
            emit(
                MediaEvent.StageChanged(
                    MediaStage.DownloadAudio,
                    "Downloading HLS audio fragments locally"
                )
            )
            emit(MediaEvent.WorkingFileResolved(hlsWorkingFile.absolutePath))
            emit(
                MediaEvent.StageChanged(
                    MediaStage.ConvertOutput,
                    "Transcoding downloaded HLS audio into mp3"
                )
            )
            transcoder.transcodeFileToMp3(
                inputFile = hlsWorkingFile,
                outputFile = outputFile,
                durationSeconds = resolved.metadata.durationSeconds,
                emit = emit,
            )
            hlsWorkingFile.delete()
            emit(MediaEvent.LogEmitted("Android HLS local fragment transcode finished"))
        }

        emit(
            MediaEvent.StageChanged(
                MediaStage.Finalize,
                "Cleaning temporary files and finalizing output"
            )
        )
        emit(MediaEvent.Completed(outputFile.absolutePath))

        AudioDownloadResult(
            path = outputFile.absolutePath,
            fileName = outputFile.name,
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

    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): JsonObject {
        val response = requestBytes(
            url = url,
            method = HttpMethod.Post,
            headers = headers,
            body = body.toString().toByteArray(StandardCharsets.UTF_8),
        )
        if (response.statusCode !in 200..299) throw IllegalStateException("HTTP ${response.statusCode}")
        return Json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8)) as JsonObject
    }

    private suspend fun downloadFirstAvailableFormat(
        formats: List<NativeAudioFormat>,
        output: File,
        refererUrl: String,
        emit: suspend (MediaEvent) -> Unit,
    ): NativeAudioFormat {
        var lastError: Exception? = null
        for (format in formats) {
            runCatching {
                output.delete()
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
        output: File,
        refererUrl: String,
        emit: suspend (MediaEvent) -> Unit,
    ) {
        val progressTracker = ProgressStepTracker()
        client.prepareGet(format.url ?: throw IllegalStateException("Missing format url")) {
            headers {
                append("User-Agent", userAgentForSource(format.source))
                append("Accept-Encoding", "identity")
                append("Accept", "*/*")
                append("Origin", "https://www.youtube.com")
                append("Referer", refererUrl)
                append("Range", "bytes=0-")
                if (format.source == "ios-player-api") {
                    append("X-YouTube-Client-Name", "5")
                    append("X-YouTube-Client-Version", YoutubeConstants.IOS_CLIENT_VERSION)
                }
            }
        }.execute { response ->
            if (response.status.value !in 200..299) {
                throw IllegalStateException("Native direct download returned HTTP ${response.status.value} for ${format.describe()}")
            }

            val totalBytes = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0L }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
            var written = 0L

            output.outputStream().buffered(DOWNLOAD_BUFFER_SIZE_BYTES).use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    written += read

                    if (progressTracker.shouldEmit(totalBytes, written)) {
                        emit(
                            MediaEvent.ProgressChanged(
                                TransferSnapshot(
                                    label = "Downloading audio stream",
                                    progressPercent = progressTracker.percent(totalBytes, written),
                                    downloadedBytes = written,
                                    totalBytes = totalBytes,
                                    speedText = format.describe(),
                                )
                            )
                        )
                    }
                }
                out.flush()
            }

            if (progressTracker.shouldEmit(totalBytes, written, force = true)) {
                emit(
                    MediaEvent.ProgressChanged(
                        TransferSnapshot(
                            label = "Downloading audio stream",
                            progressPercent = progressTracker.percent(totalBytes, written),
                            downloadedBytes = written,
                            totalBytes = totalBytes,
                            speedText = format.describe(),
                        )
                    )
                )
            }
        }
    }

    private suspend fun getText(url: String, headers: Map<String, String>): String {
        val response = requestBytes(url, HttpMethod.Get, headers, null)
        if (response.statusCode !in 200..299) throw IllegalStateException("Watch page returned HTTP ${response.statusCode}")
        return response.body.toString(charsetFromContentType(response.contentType))
    }

    private suspend fun selectAndroidPlayableManifestUrl(
        masterManifestUrl: String,
        headers: Map<String, String>,
        emit: suspend (MediaEvent) -> Unit,
    ): String {
        val manifestText = runCatching { getText(masterManifestUrl, headers) }
            .getOrElse { error ->
                emit(MediaEvent.LogEmitted("Android manifest fetch failed, using original URL: ${error.message}"))
                return masterManifestUrl
            }

        val audioPlaylists = manifestText.lineSequence()
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .mapNotNull { line -> parseHlsAttributes(line.removePrefix("#EXT-X-MEDIA:")) }
            .filter { attrs -> attrs["TYPE"] == "AUDIO" }
            .mapNotNull { attrs -> attrs["URI"] }
            .map { resolveManifestUri(masterManifestUrl, it) }
            .toList()

        if (audioPlaylists.isEmpty()) {
            emit(MediaEvent.LogEmitted("Android manifest parser found no explicit audio playlists; using master manifest"))
            return masterManifestUrl
        }

        val selected = audioPlaylists.maxByOrNull { url ->
            Regex("""/itag/(\d+)/""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        } ?: audioPlaylists.first()

        emit(MediaEvent.LogEmitted("Android manifest parser selected audio playlist URL"))
        return selected
    }

    private suspend fun downloadHlsAudioToLocalFile(
        manifestUrl: String,
        headers: Map<String, String>,
        stagingDirectory: File,
        emit: suspend (MediaEvent) -> Unit,
    ): File {
        val playlistText = getText(manifestUrl, headers)
        val lines =
            playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        val initSegment = lines.firstNotNullOfOrNull { line ->
            if (!line.startsWith("#EXT-X-MAP:")) return@firstNotNullOfOrNull null
            parseHlsAttributes(line.removePrefix("#EXT-X-MAP:"))?.get("URI")
        }?.let { resolveManifestUri(manifestUrl, it) }

        val segmentUrls = lines
            .filter { !it.startsWith("#") }
            .map { resolveManifestUri(manifestUrl, it) }

        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("Android HLS downloader found no media segments in playlist")
        }

        val outputFile =
            File(stagingDirectory, if (initSegment != null) "audio-hls.mp4" else "audio-hls.aac")
        emit(
            MediaEvent.LogEmitted(
                "Android HLS downloader segments=${segmentUrls.size} parallelism=${
                    minOf(
                        HLS_DOWNLOAD_PARALLELISM,
                        segmentUrls.size
                    )
                }"
            )
        )
        outputFile.parentFile?.mkdirs()
        var strippedId3Logged = false
        var totalWritten = 0L
        outputFile.outputStream().use { out ->
            initSegment?.let { url ->
                emit(MediaEvent.LogEmitted("Android HLS downloader writing init segment"))
                val bytes = requestBytes(url, HttpMethod.Get, headers, null).body
                out.write(bytes)
                totalWritten += bytes.size
            }

            var completedSegments = 0
            for (batch in segmentUrls.chunked(HLS_DOWNLOAD_PARALLELISM)) {
                val downloadedBatch = parallelMapBatched(batch, batch.size) { url ->
                    requestBytes(url, HttpMethod.Get, headers, null).body
                }
                downloadedBatch.forEach { originalBytes ->
                    val bytes =
                        if (initSegment == null) HlsAudioSegments.stripLeadingId3Tags(originalBytes) else originalBytes
                    if (!strippedId3Logged && bytes.size != originalBytes.size) {
                        strippedId3Logged = true
                        emit(MediaEvent.LogEmitted("Android HLS downloader stripped leading ID3 metadata from AAC segments"))
                    }
                    out.write(bytes)
                    totalWritten += bytes.size
                    completedSegments++
                    val percent =
                        (((completedSegments).toDouble() / segmentUrls.size) * 100).roundToInt()
                            .coerceIn(0, 100)
                    if (completedSegments == 1 || completedSegments == segmentUrls.size || percent % 10 == 0) {
                        emit(MediaEvent.LogEmitted("Android HLS downloader progress: segment $completedSegments/${segmentUrls.size}"))
                    }
                    emit(
                        MediaEvent.ProgressChanged(
                            TransferSnapshot(
                                label = "Downloading HLS audio fragments",
                                progressPercent = percent,
                                downloadedBytes = totalWritten,
                            )
                        )
                    )
                }
            }
        }
        emit(MediaEvent.LogEmitted("Android HLS downloader wrote local fragment file: ${outputFile.absolutePath}"))
        return outputFile
    }

    private fun resolveManifestUri(baseUrl: String, uri: String): String {
        return when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            uri.startsWith("//") -> "https:$uri"
            uri.startsWith("/") -> {
                val origin = URL(baseUrl).let { "${it.protocol}://${it.host}" }
                "$origin$uri"
            }

            else -> {
                val base = baseUrl.substringBeforeLast('/')
                "$base/$uri"
            }
        }
    }

    private fun parseHlsAttributes(raw: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < raw.length) {
            while (index < raw.length && (raw[index] == ' ' || raw[index] == ',')) index++
            if (index >= raw.length) break

            val keyStart = index
            while (index < raw.length && raw[index] != '=') index++
            if (index >= raw.length) return null
            val key = raw.substring(keyStart, index)
            index++

            val value = if (index < raw.length && raw[index] == '"') {
                index++
                val valueStart = index
                while (index < raw.length && raw[index] != '"') index++
                if (index > raw.length) return null
                val out = raw.substring(valueStart, index)
                index++
                out
            } else {
                val valueStart = index
                while (index < raw.length && raw[index] != ',') index++
                raw.substring(valueStart, index)
            }

            result[key] = value
            if (index < raw.length && raw[index] == ',') index++
        }
        return result
    }

    private suspend fun requestBytes(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): YoutubeHttpResult {
        val response = client.request(url) {
            this.method = method
            headers { headers.forEach { (key, value) -> append(key, value) } }
            body?.let { setBody(it) }
        }
        return YoutubeHttpResult(
            statusCode = response.status.value,
            body = decodeResponseBody(response.bodyAsBytes(), response.headers["Content-Encoding"]),
            contentType = response.headers["Content-Type"],
        )
    }

    private fun decodeResponseBody(bytes: ByteArray, contentEncoding: String?): ByteArray {
        return when (contentEncoding?.lowercase()) {
            "gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes)).readBytes()
            else -> bytes
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

    private fun resolveOutputFile(options: AudioDownloadOptions, title: String): File {
        val safeTitle =
            title.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim()
                .take(120).ifBlank { "youtube-audio" }
        val defaultFileName = "$safeTitle.mp3"
        val outputTarget = options.outputPath

        if (outputTarget.isNullOrBlank()) return uniqueFile(
            File(
                context.filesDir,
                defaultFileName
            ).absoluteFile
        )

        val raw = File(outputTarget)
        val looksLikeDirectory =
            outputTarget.endsWith("/") || outputTarget.endsWith("\\") || !raw.name.contains('.')
        val resolved = when {
            raw.exists() && raw.isDirectory -> File(raw, defaultFileName)
            looksLikeDirectory -> File(raw, defaultFileName)
            raw.name.endsWith(".mp3", ignoreCase = true) -> raw
            else -> File(raw.parentFile ?: context.filesDir, raw.name + ".mp3")
        }.absoluteFile

        resolved.parentFile?.mkdirs()
        return uniqueFile(resolved)
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        var attempt = 1
        while (true) {
            val candidate = File(file.parentFile, "$stem ($attempt)$ext")
            if (!candidate.exists()) return candidate
            attempt++
        }
    }

}

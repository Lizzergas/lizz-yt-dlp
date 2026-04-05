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
import dev.lizz.ytdl.providers.youtube.hls.HlsAudioSegments
import dev.lizz.ytdl.providers.youtube.perf.DOWNLOAD_BUFFER_SIZE_BYTES
import dev.lizz.ytdl.providers.youtube.perf.HLS_DOWNLOAD_PARALLELISM
import dev.lizz.ytdl.providers.youtube.perf.ProgressStepTracker
import dev.lizz.ytdl.providers.youtube.perf.parallelMapBatched
import dev.lizz.ytdl.providers.youtube.probe.YoutubeAudioPlanner
import dev.lizz.ytdl.providers.youtube.probe.YoutubeProbeService
import dev.lizz.ytdl.providers.youtube.probe.YoutubeProbeTransport
import dev.lizz.ytdl.providers.youtube.probe.userAgentForSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
internal class IosYoutubeProvider(
    private val commons: YoutubeExtractorCommons = YoutubeExtractorCommons(),
    private val transcoder: IosMp3Transcoder = IosMp3Transcoder(),
    private val client: HttpClient = HttpClient(Darwin) {
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
            this@IosYoutubeProvider.getText(url, headers)

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: JsonObject,
        ): JsonObject = this@IosYoutubeProvider.postJson(url, headers, body)
    }

    override fun canHandle(locator: String): Boolean = YoutubeProviderSupport.canHandle(locator)

    override suspend fun downloadAudio(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit,
    ): AudioDownloadResult = withContext(Dispatchers.Default) {
        val probe = probeService.probe(request.url, probeTransport, emit = emit)
        val resolved = probe.resolvedMedia
        val watchData = probe.watchData
        emit(MediaEvent.MetadataResolved(resolved.metadata))

        val audioPlan = YoutubeAudioPlanner.plan(
            resolved,
            manifestPriority = listOf("hls"),
            preferredFormatPicker = ::pickIosPreferredAudioFormat
        )
        audioPlan.preferredDirectFormat?.let { emit(MediaEvent.LogEmitted("Native engine selected ${it.describe()}")) }

        val tempDir = temporaryDirectory().also { ensureDirectory(it) }
        val outputPath = resolveOutputPath(request.options, resolved.metadata.title)
        emit(MediaEvent.OutputResolved(outputPath))

        val directDownloadSucceeded = if (audioPlan.preferredDirectFormat == null) {
            false
        } else {
            val workingPath = "$tempDir/audio.${audioPlan.preferredDirectFormat.ext}"
            emit(
                MediaEvent.StageChanged(
                    MediaStage.DownloadAudio,
                    "Downloading direct audio stream resolved by native engine"
                )
            )
            runCatching {
                val downloadedFormat = downloadFirstAvailableFormat(
                    audioPlan.directFormats,
                    workingPath,
                    watchData.canonicalUrl,
                    emit
                )
                emit(MediaEvent.WorkingFileResolved(workingPath))
                emit(MediaEvent.LogEmitted("Downloaded using ${downloadedFormat.describe()}"))

                emit(
                    MediaEvent.StageChanged(
                        MediaStage.ConvertOutput,
                        "Transcoding the downloaded audio stream into mp3"
                    )
                )
                transcoder.transcodeFileToMp3(
                    workingPath,
                    outputPath,
                    resolved.metadata.durationSeconds,
                    emit
                )
                deleteFileIfExists(workingPath)
                true
            }.getOrElse { error ->
                emit(MediaEvent.LogEmitted("Direct media URLs were not usable: ${error.message}"))
                deleteFileIfExists(workingPath)
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
                selectIosPlayableManifestUrl(manifest.url, manifestHeaders, emit)
            emit(MediaEvent.LogEmitted("iOS manifest URL: $playableManifestUrl"))
            val hlsWorkingPath = downloadHlsAudioToLocalFile(
                manifestUrl = playableManifestUrl,
                headers = manifestHeaders,
                temporaryDirectory = tempDir,
                emit = emit,
            )
            emit(
                MediaEvent.StageChanged(
                    MediaStage.DownloadAudio,
                    "Downloading HLS audio fragments locally"
                )
            )
            emit(MediaEvent.WorkingFileResolved(hlsWorkingPath))
            emit(
                MediaEvent.StageChanged(
                    MediaStage.ConvertOutput,
                    "Transcoding downloaded HLS audio into mp3"
                )
            )
            transcoder.transcodeFileToMp3(
                hlsWorkingPath,
                outputPath,
                resolved.metadata.durationSeconds,
                emit
            )
            deleteFileIfExists(hlsWorkingPath)
            emit(MediaEvent.LogEmitted("iOS HLS local fragment transcode finished"))
        }

        emit(
            MediaEvent.StageChanged(
                MediaStage.Finalize,
                "Cleaning temporary files and finalizing output"
            )
        )
        emit(MediaEvent.Completed(outputPath))
        AudioDownloadResult(
            path = outputPath,
            fileName = outputPath.substringAfterLast('/'),
            metadata = resolved.metadata,
            provider = id
        )
    }

    override suspend fun getTranscript(request: TranscriptRequest): String? {
        val transcript = getTranscriptCues(request) ?: return null
        return YoutubeTranscriptFormatter.format(transcript, request.includeTimecodes)
    }

    override suspend fun getTranscriptCues(request: TranscriptRequest): TranscriptResult? =
        withContext(Dispatchers.Default) {
            val probe = probeService.probe(request.url, probeTransport, emit = {})
            probeService.getTranscriptCues(probe, request, probeTransport)
        }

    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): JsonObject {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            headers { headers.forEach { (key, value) -> append(key, value) } }
            setBody(body.toString())
        }
        if (response.status.value !in 200..299) throw IllegalStateException("HTTP ${response.status.value}")
        return Json.parseToJsonElement(response.bodyAsText()) as JsonObject
    }

    private suspend fun downloadFirstAvailableFormat(
        formats: List<NativeAudioFormat>,
        outputPath: String,
        refererUrl: String,
        emit: suspend (MediaEvent) -> Unit,
    ): NativeAudioFormat {
        var lastError: Exception? = null
        for (format in formats) {
            runCatching {
                client.prepareGet(format.url ?: error("Missing format url")) {
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

                    val totalBytes =
                        response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0L }
                    val progressTracker = ProgressStepTracker()
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
                    var written = 0L
                    val file = fopen(outputPath, "wb")
                        ?: throw IllegalStateException("Failed to open iOS file for writing: $outputPath")
                    try {
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read == 0) continue
                            writeToFile(file, buffer, read)
                            written += read
                            if (progressTracker.shouldEmit(totalBytes, written)) {
                                emit(
                                    MediaEvent.ProgressChanged(
                                        TransferSnapshot(
                                            label = "Downloading audio stream",
                                            progressPercent = progressTracker.percent(
                                                totalBytes,
                                                written
                                            ),
                                            downloadedBytes = written,
                                            totalBytes = totalBytes,
                                            speedText = format.describe(),
                                        )
                                    )
                                )
                            }
                        }
                    } finally {
                        fclose(file)
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

    private suspend fun getText(url: String, headersMap: Map<String, String>): String {
        val response = client.get(url) {
            headers { headersMap.forEach { (key, value) -> append(key, value) } }
        }
        if (response.status.value !in 200..299) throw IllegalStateException("Watch page returned HTTP ${response.status.value}")
        return response.bodyAsText()
    }

    private suspend fun selectIosPlayableManifestUrl(
        masterManifestUrl: String,
        headers: Map<String, String>,
        emit: suspend (MediaEvent) -> Unit,
    ): String {
        val manifestText = runCatching { getText(masterManifestUrl, headers) }
            .getOrElse { error ->
                emit(MediaEvent.LogEmitted("iOS manifest fetch failed, using original URL: ${error.message}"))
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
            emit(MediaEvent.LogEmitted("iOS manifest parser found no explicit audio playlists; using master manifest"))
            return masterManifestUrl
        }

        val selected = audioPlaylists.maxByOrNull { url ->
            Regex("""/itag/(\d+)/""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        } ?: audioPlaylists.first()

        emit(MediaEvent.LogEmitted("iOS manifest parser selected audio playlist URL"))
        return selected
    }

    private suspend fun downloadHlsAudioToLocalFile(
        manifestUrl: String,
        headers: Map<String, String>,
        temporaryDirectory: String,
        emit: suspend (MediaEvent) -> Unit,
    ): String {
        val playlistText = getText(manifestUrl, headers)
        val lines =
            playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        val initSegment = lines.firstNotNullOfOrNull { line ->
            if (!line.startsWith("#EXT-X-MAP:")) return@firstNotNullOfOrNull null
            parseHlsAttributes(line.removePrefix("#EXT-X-MAP:"))?.get("URI")
        }?.let { resolveManifestUri(manifestUrl, it) }

        val segmentUrls =
            lines.filter { !it.startsWith("#") }.map { resolveManifestUri(manifestUrl, it) }
        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("iOS HLS downloader found no media segments in playlist")
        }

        val outputPath =
            if (initSegment != null) "$temporaryDirectory/audio-hls.mp4" else "$temporaryDirectory/audio-hls.aac"
        emit(
            MediaEvent.LogEmitted(
                "iOS HLS downloader segments=${segmentUrls.size} parallelism=${
                    minOf(
                        HLS_DOWNLOAD_PARALLELISM,
                        segmentUrls.size
                    )
                }"
            )
        )
        var strippedId3Logged = false
        var totalWritten = 0L
        initSegment?.let { url ->
            emit(MediaEvent.LogEmitted("iOS HLS downloader writing init segment"))
            val bytes = client.get(url) {
                headers {
                    headers.forEach { (key, value) ->
                        append(
                            key,
                            value
                        )
                    }
                }
            }.bodyAsBytes()
            appendBytes(outputPath, bytes)
            totalWritten += bytes.size
        }
        var completedSegments = 0
        for (batch in segmentUrls.chunked(HLS_DOWNLOAD_PARALLELISM)) {
            val downloadedBatch = parallelMapBatched(batch, batch.size) { url ->
                client.get(url) {
                    headers {
                        headers.forEach { (key, value) ->
                            append(
                                key,
                                value
                            )
                        }
                    }
                }.bodyAsBytes()
            }
            downloadedBatch.forEach { originalBytes ->
                val bytes =
                    if (initSegment == null) HlsAudioSegments.stripLeadingId3Tags(originalBytes) else originalBytes
                if (!strippedId3Logged && bytes.size != originalBytes.size) {
                    strippedId3Logged = true
                    emit(MediaEvent.LogEmitted("iOS HLS downloader stripped leading ID3 metadata from AAC segments"))
                }
                appendBytes(outputPath, bytes)
                totalWritten += bytes.size
                completedSegments++
                val percent =
                    (((completedSegments).toDouble() / segmentUrls.size) * 100).roundToInt()
                        .coerceIn(0, 100)
                if (completedSegments == 1 || completedSegments == segmentUrls.size || percent % 10 == 0) {
                    emit(MediaEvent.LogEmitted("iOS HLS downloader progress: segment $completedSegments/${segmentUrls.size}"))
                }
                emit(
                    MediaEvent.ProgressChanged(
                        TransferSnapshot(
                            label = "Downloading HLS audio fragments",
                            progressPercent = percent,
                            downloadedBytes = totalWritten
                        )
                    )
                )
            }
        }
        emit(MediaEvent.LogEmitted("iOS HLS downloader wrote local fragment file: $outputPath"))
        return outputPath
    }

    private fun resolveManifestUri(baseUrl: String, uri: String): String {
        return when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            uri.startsWith("//") -> "https:$uri"
            uri.startsWith("/") -> {
                val nsUrl = platform.Foundation.NSURL.URLWithString(baseUrl)!!
                val origin = "${nsUrl.scheme}://${nsUrl.host}"
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

    private fun temporaryDirectory(): String =
        platform.Foundation.NSTemporaryDirectory().trimEnd('/')

    private fun documentsDirectory(): String {
        val path = NSHomeDirectory() + "/Documents"
        ensureDirectory(path)
        return path
    }

    private fun ensureDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    private fun deleteFileIfExists(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    private fun resolveOutputPath(options: AudioDownloadOptions, title: String): String {
        val safeTitle =
            title.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim()
                .take(120).ifBlank { "youtube-audio" }
        val defaultFileName = "$safeTitle.mp3"
        val outputTarget = options.outputPath
        if (outputTarget.isNullOrBlank()) return uniquePath("${documentsDirectory()}/$defaultFileName")
        val looksLikeDirectory =
            outputTarget.endsWith("/") || !outputTarget.substringAfterLast('/').contains('.')
        val resolved =
            if (looksLikeDirectory) "$outputTarget/$defaultFileName" else if (outputTarget.endsWith(
                    ".mp3"
                )
            ) outputTarget else "$outputTarget.mp3"
        return uniquePath(resolved)
    }

    private fun uniquePath(path: String): String {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return path
        val dot = path.lastIndexOf('.')
        val stem = if (dot >= 0) path.substring(0, dot) else path
        val ext = if (dot >= 0) path.substring(dot) else ""
        var attempt = 1
        while (true) {
            val candidate = "$stem ($attempt)$ext"
            if (!NSFileManager.defaultManager.fileExistsAtPath(candidate)) return candidate
            attempt++
        }
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb")
            ?: throw IllegalStateException("Failed to open iOS file for writing: $path")
        try {
            writeToFile(file, bytes, bytes.size)
        } finally {
            fclose(file)
        }
    }

    private fun appendBytes(path: String, bytes: ByteArray) {
        val file = fopen(path, "ab")
            ?: throw IllegalStateException("Failed to open iOS file for appending: $path")
        try {
            writeToFile(file, bytes, bytes.size)
        } finally {
            fclose(file)
        }
    }

    private fun writeToFile(file: CPointer<FILE>?, bytes: ByteArray, count: Int) {
        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), count.convert(), file)
        }
    }

    private fun pickIosPreferredAudioFormat(formats: List<NativeAudioFormat>): NativeAudioFormat {
        return formats.filter { it.url != null }.maxWithOrNull(
            compareBy(
                { if (it.videoCodec == null) 1 else 0 },
                { if (it.mimeType?.startsWith("audio/mp4") == true || it.audioCodec?.startsWith("mp4a") == true) 2 else 0 },
                { if (it.ext == "m4a" || it.ext == "mp4") 1 else 0 },
                { it.averageBitrate ?: -1.0 },
            )
        ) ?: commons.pickBestAudioFormat(formats)
    }
}

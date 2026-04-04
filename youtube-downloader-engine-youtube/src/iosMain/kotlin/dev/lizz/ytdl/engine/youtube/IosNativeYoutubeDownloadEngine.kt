package dev.lizz.ytdl.engine.youtube

import dev.lizz.ytdl.core.DownloadEvent
import dev.lizz.ytdl.core.DownloadOptions
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.core.DownloadResult
import dev.lizz.ytdl.core.DownloadStage
import dev.lizz.ytdl.core.TransferSnapshot
import dev.lizz.ytdl.core.YoutubeDownloadEngine
import dev.lizz.ytdl.engine.youtube.hls.HlsAudioSegments
import dev.lizz.ytdl.engine.youtube.perf.DOWNLOAD_BUFFER_SIZE_BYTES
import dev.lizz.ytdl.engine.youtube.perf.HLS_DOWNLOAD_PARALLELISM
import dev.lizz.ytdl.engine.youtube.perf.ProgressStepTracker
import dev.lizz.ytdl.engine.youtube.perf.parallelMapBatched
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import io.ktor.utils.io.readAvailable
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
internal class IosNativeYoutubeDownloadEngine(
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
) : YoutubeDownloadEngine {
    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult = withContext(Dispatchers.Default) {
        emit(DownloadEvent.StageChanged(DownloadStage.PrepareBackend, "Resolving YouTube watch page in native Kotlin iOS engine"))

        val watchPageHtml = getText(
            request.url,
            mapOf(
                "User-Agent" to YoutubeConstants.BROWSER_USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate",
            ),
        )

        emit(DownloadEvent.StageChanged(DownloadStage.ProbeMetadata, "Parsing watch page and requesting Innertube player responses"))
        val watchData = commons.parseWatchData(request.url, watchPageHtml)
        val apiPlayers = callPlayerApis(watchData.videoId, watchData.ytcfg, emit)
        val initialResolved = commons.resolveMedia(request.url, watchData.initialPlayerResponse, apiPlayers)
        val resolved = attemptProtectedFormatResolution(initialResolved, watchData.playerUrl, emit)
        emit(DownloadEvent.MetadataResolved(resolved.metadata))

        val selectedFormat = pickIosPreferredAudioFormat(resolved.audioFormats)
        val orderedFormats = (listOf(selectedFormat) + resolved.audioFormats.filterNot { it.url == selectedFormat.url })
            .filter { it.url != null }
        emit(DownloadEvent.LogEmitted("Native engine selected ${selectedFormat.describe()}"))

        val tempDir = temporaryDirectory().also { ensureDirectory(it) }
        val workingPath = "$tempDir/audio.${selectedFormat.ext}"
        val outputPath = resolveOutputPath(request.options, resolved.metadata.title)
        emit(DownloadEvent.OutputResolved(outputPath))

        emit(DownloadEvent.StageChanged(DownloadStage.DownloadAudio, "Downloading direct audio stream resolved by native engine"))
        val directDownloadSucceeded = runCatching {
            val downloadedFormat = downloadFirstAvailableFormat(orderedFormats, workingPath, watchData.canonicalUrl, emit)
            emit(DownloadEvent.WorkingFileResolved(workingPath))
            emit(DownloadEvent.LogEmitted("Downloaded using ${downloadedFormat.describe()}"))

            emit(DownloadEvent.StageChanged(DownloadStage.ConvertToMp3, "Transcoding the downloaded audio stream into mp3"))
            transcoder.transcodeFileToMp3(workingPath, outputPath, resolved.metadata.durationSeconds, emit)
            true
        }.getOrElse { error ->
            emit(DownloadEvent.LogEmitted("Direct media URLs were not usable: ${error.message}"))
            false
        }

        if (!directDownloadSucceeded) {
            val manifest = resolved.hlsManifestUrls.firstOrNull()
                ?: throw IllegalStateException("Direct download failed and no HLS manifest fallback was available")
            emit(DownloadEvent.LogEmitted("Falling back to ${manifest.kind.uppercase()} manifest from ${manifest.source}"))
            val manifestHeaders = mapOf(
                "Referer" to watchData.canonicalUrl,
                "Origin" to "https://www.youtube.com",
                "User-Agent" to userAgentForSource(manifest.source),
            )
            val playableManifestUrl = selectIosPlayableManifestUrl(manifest.url, manifestHeaders, emit)
            emit(DownloadEvent.LogEmitted("iOS manifest URL: $playableManifestUrl"))
            val hlsWorkingPath = downloadHlsAudioToLocalFile(
                manifestUrl = playableManifestUrl,
                headers = manifestHeaders,
                temporaryDirectory = tempDir,
                emit = emit,
            )
            emit(DownloadEvent.StageChanged(DownloadStage.DownloadAudio, "Downloading HLS audio fragments locally"))
            emit(DownloadEvent.WorkingFileResolved(hlsWorkingPath))
            emit(DownloadEvent.StageChanged(DownloadStage.ConvertToMp3, "Transcoding downloaded HLS audio into mp3"))
            transcoder.transcodeFileToMp3(hlsWorkingPath, outputPath, resolved.metadata.durationSeconds, emit)
            deleteFileIfExists(hlsWorkingPath)
            emit(DownloadEvent.LogEmitted("iOS HLS local fragment transcode finished"))
        }

        deleteFileIfExists(workingPath)
        emit(DownloadEvent.StageChanged(DownloadStage.Finalize, "Cleaning temporary files and finalizing output"))
        emit(DownloadEvent.Completed(outputPath))
        DownloadResult(path = outputPath, fileName = outputPath.substringAfterLast('/'), metadata = resolved.metadata)
    }

    private suspend fun callPlayerApis(videoId: String, ytcfg: JsonObject, emit: suspend (DownloadEvent) -> Unit): List<Pair<String, JsonObject>> {
        val apiKey = ytcfg.string("INNERTUBE_API_KEY") ?: throw IllegalStateException("Watch page is missing INNERTUBE_API_KEY")
        val clients = commons.createInnertubeClients(ytcfg)
        val results = mutableListOf<Pair<String, JsonObject>>()
        for (clientConfig in clients) {
            try {
                val player = postPlayerRequest(apiKey, videoId, clientConfig)
                emit(DownloadEvent.LogEmitted("${clientConfig.label} responded with ${summarize(player)}"))
                results += clientConfig.label to player
            } catch (e: Exception) {
                emit(DownloadEvent.LogEmitted("${clientConfig.label} unavailable: ${e.message}"))
            }
        }
        return results
    }

    private suspend fun postPlayerRequest(apiKey: String, videoId: String, clientConfig: PlayerClientConfig): JsonObject {
        val body = buildJsonObject {
            put("context", clientConfig.context)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
        val response = client.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$apiKey") {
            contentType(ContentType.Application.Json)
            headers {
                append("User-Agent", clientConfig.userAgent)
                append("X-YouTube-Client-Name", clientConfig.headerClientName)
                append("X-YouTube-Client-Version", clientConfig.headerClientVersion)
                append("Accept-Encoding", "gzip, deflate")
                append("Origin", "https://www.youtube.com")
                clientConfig.visitorData?.let { append("X-Goog-Visitor-Id", it) }
            }
            setBody(body)
        }
        if (response.status.value !in 200..299) throw IllegalStateException("HTTP ${response.status.value}")
        return Json.parseToJsonElement(response.bodyAsText()) as JsonObject
    }

    private suspend fun attemptProtectedFormatResolution(
        resolved: ResolvedYoutubeMedia,
        playerUrl: String?,
        emit: suspend (DownloadEvent) -> Unit,
    ): ResolvedYoutubeMedia {
        if (resolved.audioFormats.all { it.url != null } || playerUrl == null) return resolved
        emit(DownloadEvent.LogEmitted("Protected audio formats detected; fetching player JS from $playerUrl"))
        val directBefore = resolved.audioFormats.count { it.url != null }
        val playerJs = getText(playerUrl, mapOf("User-Agent" to YoutubeConstants.BROWSER_USER_AGENT, "Accept-Encoding" to "gzip, deflate"))
        val solved = PlayerJsDecipherer.resolveProtectedFormats(resolved.audioFormats, playerJs)
        val directAfter = solved.count { it.url != null }
        emit(DownloadEvent.LogEmitted("Native signature solver resolved ${directAfter - directBefore} protected audio format(s)"))
        return resolved.copy(audioFormats = solved)
    }

    private suspend fun downloadFirstAvailableFormat(
        formats: List<NativeAudioFormat>,
        outputPath: String,
        refererUrl: String,
        emit: suspend (DownloadEvent) -> Unit,
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

                    val totalBytes = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0L }
                    val progressTracker = ProgressStepTracker()
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
                    var written = 0L
                    val file = fopen(outputPath, "wb") ?: throw IllegalStateException("Failed to open iOS file for writing: $outputPath")
                    try {
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read == 0) continue
                            writeToFile(file, buffer, read)
                            written += read
                            if (progressTracker.shouldEmit(totalBytes, written)) {
                                emit(
                                    DownloadEvent.ProgressChanged(
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
                    } finally {
                        fclose(file)
                    }

                    if (progressTracker.shouldEmit(totalBytes, written, force = true)) {
                        emit(
                            DownloadEvent.ProgressChanged(
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
                    lastError = error as? Exception ?: IllegalStateException(error.message ?: error.toString())
                    emit(DownloadEvent.LogEmitted("Format ${format.describe()} was rejected, trying fallback: ${error.message}"))
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
        emit: suspend (DownloadEvent) -> Unit,
    ): String {
        val manifestText = runCatching { getText(masterManifestUrl, headers) }
            .getOrElse { error ->
                emit(DownloadEvent.LogEmitted("iOS manifest fetch failed, using original URL: ${error.message}"))
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
            emit(DownloadEvent.LogEmitted("iOS manifest parser found no explicit audio playlists; using master manifest"))
            return masterManifestUrl
        }

        val selected = audioPlaylists.maxByOrNull { url ->
            Regex("""/itag/(\d+)/""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        } ?: audioPlaylists.first()

        emit(DownloadEvent.LogEmitted("iOS manifest parser selected audio playlist URL"))
        return selected
    }

    private suspend fun downloadHlsAudioToLocalFile(
        manifestUrl: String,
        headers: Map<String, String>,
        temporaryDirectory: String,
        emit: suspend (DownloadEvent) -> Unit,
    ): String {
        val playlistText = getText(manifestUrl, headers)
        val lines = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        val initSegment = lines.firstNotNullOfOrNull { line ->
            if (!line.startsWith("#EXT-X-MAP:")) return@firstNotNullOfOrNull null
            parseHlsAttributes(line.removePrefix("#EXT-X-MAP:"))?.get("URI")
        }?.let { resolveManifestUri(manifestUrl, it) }

        val segmentUrls = lines.filter { !it.startsWith("#") }.map { resolveManifestUri(manifestUrl, it) }
        if (segmentUrls.isEmpty()) {
            throw IllegalStateException("iOS HLS downloader found no media segments in playlist")
        }

        val outputPath = if (initSegment != null) "$temporaryDirectory/audio-hls.mp4" else "$temporaryDirectory/audio-hls.aac"
        emit(DownloadEvent.LogEmitted("iOS HLS downloader segments=${segmentUrls.size} parallelism=${minOf(HLS_DOWNLOAD_PARALLELISM, segmentUrls.size)}"))
        var strippedId3Logged = false
        var totalWritten = 0L
        initSegment?.let { url ->
            emit(DownloadEvent.LogEmitted("iOS HLS downloader writing init segment"))
            val bytes = client.get(url) { headers { headers.forEach { (key, value) -> append(key, value) } } }.bodyAsBytes()
            appendBytes(outputPath, bytes)
            totalWritten += bytes.size
        }
        var completedSegments = 0
        for (batch in segmentUrls.chunked(HLS_DOWNLOAD_PARALLELISM)) {
            val downloadedBatch = parallelMapBatched(batch, batch.size) { url ->
                client.get(url) { headers { headers.forEach { (key, value) -> append(key, value) } } }.bodyAsBytes()
            }
            downloadedBatch.forEach { originalBytes ->
                val bytes = if (initSegment == null) HlsAudioSegments.stripLeadingId3Tags(originalBytes) else originalBytes
                if (!strippedId3Logged && bytes.size != originalBytes.size) {
                    strippedId3Logged = true
                    emit(DownloadEvent.LogEmitted("iOS HLS downloader stripped leading ID3 metadata from AAC segments"))
                }
                appendBytes(outputPath, bytes)
                totalWritten += bytes.size
                completedSegments++
                val percent = (((completedSegments).toDouble() / segmentUrls.size) * 100).roundToInt().coerceIn(0, 100)
                if (completedSegments == 1 || completedSegments == segmentUrls.size || percent % 10 == 0) {
                    emit(DownloadEvent.LogEmitted("iOS HLS downloader progress: segment $completedSegments/${segmentUrls.size}"))
                }
                emit(DownloadEvent.ProgressChanged(TransferSnapshot(label = "Downloading HLS audio fragments", progressPercent = percent, downloadedBytes = totalWritten)))
            }
        }
        emit(DownloadEvent.LogEmitted("iOS HLS downloader wrote local fragment file: $outputPath"))
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

    private fun temporaryDirectory(): String = platform.Foundation.NSTemporaryDirectory().trimEnd('/')

    private fun documentsDirectory(): String {
        val path = NSHomeDirectory() + "/Documents"
        ensureDirectory(path)
        return path
    }

    private fun ensureDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    private fun deleteFileIfExists(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    private fun resolveOutputPath(options: DownloadOptions, title: String): String {
        val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim().take(120).ifBlank { "youtube-audio" }
        val defaultFileName = "$safeTitle.mp3"
        val outputTarget = options.outputPath
        if (outputTarget.isNullOrBlank()) return uniquePath("${documentsDirectory()}/$defaultFileName")
        val looksLikeDirectory = outputTarget.endsWith("/") || !outputTarget.substringAfterLast('/').contains('.')
        val resolved = if (looksLikeDirectory) "$outputTarget/$defaultFileName" else if (outputTarget.endsWith(".mp3")) outputTarget else "$outputTarget.mp3"
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
        val file = fopen(path, "wb") ?: throw IllegalStateException("Failed to open iOS file for writing: $path")
        try {
            writeToFile(file, bytes, bytes.size)
        } finally {
            fclose(file)
        }
    }

    private fun appendBytes(path: String, bytes: ByteArray) {
        val file = fopen(path, "ab") ?: throw IllegalStateException("Failed to open iOS file for appending: $path")
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

    private fun summarize(player: JsonObject): String {
        val streamingData = player.objectAt("streamingData") ?: return "no streamingData"
        val allFormats = buildList {
            addAll(streamingData.array("formats"))
            addAll(streamingData.array("adaptiveFormats"))
        }
        val direct = allFormats.count { (it as? JsonObject)?.string("url") != null }
        val cipher = allFormats.count { (it as? JsonObject)?.string("signatureCipher") != null }
        return "direct=$direct cipher=$cipher"
    }

    private fun userAgentForSource(source: String): String = when (source) {
        "ios-player-api" -> YoutubeConstants.IOS_USER_AGENT
        "tv-player-api" -> YoutubeConstants.TV_USER_AGENT
        "android-player-api" -> YoutubeConstants.ANDROID_USER_AGENT
        else -> YoutubeConstants.BROWSER_USER_AGENT
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

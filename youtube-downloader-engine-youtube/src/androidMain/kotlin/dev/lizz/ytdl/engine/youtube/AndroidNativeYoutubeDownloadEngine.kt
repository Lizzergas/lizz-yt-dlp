package dev.lizz.ytdl.engine.youtube

import android.content.Context
import dev.lizz.ytdl.core.DownloadEvent
import dev.lizz.ytdl.core.DownloadOptions
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.core.DownloadResult
import dev.lizz.ytdl.core.DownloadStage
import dev.lizz.ytdl.core.TransferSnapshot
import dev.lizz.ytdl.core.YoutubeDownloadEngine
import dev.lizz.ytdl.engine.youtube.hls.HlsAudioSegments
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import dev.lizz.ytdl.engine.youtube.perf.DOWNLOAD_BUFFER_SIZE_BYTES
import dev.lizz.ytdl.engine.youtube.perf.HLS_DOWNLOAD_PARALLELISM
import dev.lizz.ytdl.engine.youtube.perf.ProgressStepTracker
import dev.lizz.ytdl.engine.youtube.perf.parallelMapBatched
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
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AndroidNativeYoutubeDownloadEngine(
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
) : YoutubeDownloadEngine {

    override suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        emit(DownloadEvent.StageChanged(DownloadStage.PrepareBackend, "Resolving YouTube watch page in native Kotlin Android engine"))

        val watchPageHtml = getText(
            request.url,
            mapOf(
                "User-Agent" to YoutubeExtractorCommons.BROWSER_USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate",
            )
        )

        emit(DownloadEvent.StageChanged(DownloadStage.ProbeMetadata, "Parsing watch page and requesting Innertube player responses"))
        val watchData = commons.parseWatchData(request.url, watchPageHtml)
        val apiPlayers = callPlayerApis(watchData.videoId, watchData.ytcfg, emit)
        val initialResolved = commons.resolveMedia(request.url, watchData.initialPlayerResponse, apiPlayers)
        val resolved = attemptProtectedFormatResolution(initialResolved, watchData.playerUrl, emit)
        emit(DownloadEvent.MetadataResolved(resolved.metadata))

        val stagingDirectory = File(context.cacheDir, "kt-ytdlp-native").apply { mkdirs() }
        val selectedFormat = commons.pickBestAudioFormat(resolved.audioFormats)
        val orderedFormats = (listOf(selectedFormat) + resolved.audioFormats.filterNot { it.url == selectedFormat.url })
            .filter { it.url != null }

        emit(DownloadEvent.LogEmitted("Native engine selected ${selectedFormat.describe()}"))

        val workingFile = File(stagingDirectory, "audio.${selectedFormat.ext}")
        val outputFile = resolveOutputFile(request.options, resolved.metadata.title)
        emit(DownloadEvent.OutputResolved(outputFile.absolutePath))

        emit(DownloadEvent.StageChanged(DownloadStage.DownloadAudio, "Downloading direct audio stream resolved by native engine"))
        val directDownloadSucceeded = runCatching {
            val downloadedFormat = downloadFirstAvailableFormat(orderedFormats, workingFile, watchData.canonicalUrl, emit)
            emit(DownloadEvent.WorkingFileResolved(workingFile.absolutePath))
            emit(DownloadEvent.LogEmitted("Downloaded using ${downloadedFormat.describe()}"))

            emit(DownloadEvent.StageChanged(DownloadStage.ConvertToMp3, "Transcoding the downloaded audio stream into mp3"))
            transcoder.transcodeFileToMp3(
                inputFile = workingFile,
                outputFile = outputFile,
                durationSeconds = resolved.metadata.durationSeconds,
                emit = emit,
            )
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
            val playableManifestUrl = selectAndroidPlayableManifestUrl(manifest.url, manifestHeaders, emit)
            emit(DownloadEvent.LogEmitted("Android manifest URL: $playableManifestUrl"))
            val hlsWorkingFile = downloadHlsAudioToLocalFile(
                manifestUrl = playableManifestUrl,
                headers = manifestHeaders,
                stagingDirectory = stagingDirectory,
                emit = emit,
            )
            emit(DownloadEvent.StageChanged(DownloadStage.DownloadAudio, "Downloading HLS audio fragments locally"))
            emit(DownloadEvent.WorkingFileResolved(hlsWorkingFile.absolutePath))
            emit(DownloadEvent.StageChanged(DownloadStage.ConvertToMp3, "Transcoding downloaded HLS audio into mp3"))
            transcoder.transcodeFileToMp3(
                inputFile = hlsWorkingFile,
                outputFile = outputFile,
                durationSeconds = resolved.metadata.durationSeconds,
                emit = emit,
            )
            hlsWorkingFile.delete()
            emit(DownloadEvent.LogEmitted("Android HLS local fragment transcode finished"))
        }

        emit(DownloadEvent.StageChanged(DownloadStage.Finalize, "Cleaning temporary files and finalizing output"))
        workingFile.delete()
        emit(DownloadEvent.Completed(outputFile.absolutePath))

        DownloadResult(
            path = outputFile.absolutePath,
            fileName = outputFile.name,
            metadata = resolved.metadata,
        )
    }

    private suspend fun callPlayerApis(videoId: String, ytcfg: JsonObject, emit: suspend (DownloadEvent) -> Unit): List<Pair<String, JsonObject>> {
        val apiKey = ytcfg.string("INNERTUBE_API_KEY") ?: throw IllegalStateException("Watch page is missing INNERTUBE_API_KEY")
        val clients = commons.createInnertubeClients(ytcfg)
        val results = mutableListOf<Pair<String, JsonObject>>()

        for (client in clients) {
            try {
                val player = postPlayerRequest(apiKey, videoId, client)
                emit(DownloadEvent.LogEmitted("${client.label} responded with ${summarize(player)}"))
                results += client.label to player
            } catch (e: Exception) {
                emit(DownloadEvent.LogEmitted("${client.label} unavailable: ${e.message}"))
            }
        }
        return results
    }

    private suspend fun postPlayerRequest(apiKey: String, videoId: String, client: PlayerClientConfig): JsonObject {
        val body = buildJsonObject {
            put("context", client.context)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val response = requestBytes(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$apiKey",
            method = HttpMethod.Post,
            headers = buildMap {
                put("Content-Type", "application/json")
                put("User-Agent", client.userAgent)
                put("X-YouTube-Client-Name", client.headerClientName)
                put("X-YouTube-Client-Version", client.headerClientVersion)
                put("Accept-Encoding", "gzip, deflate")
                put("Origin", "https://www.youtube.com")
                client.visitorData?.let { put("X-Goog-Visitor-Id", it) }
            },
            body = body,
        )
        if (response.statusCode !in 200..299) throw IllegalStateException("HTTP ${response.statusCode}")
        return Json.parseToJsonElement(response.body.toString(StandardCharsets.UTF_8)) as JsonObject
    }

    private suspend fun attemptProtectedFormatResolution(
        resolved: ResolvedYoutubeMedia,
        playerUrl: String?,
        emit: suspend (DownloadEvent) -> Unit,
    ): ResolvedYoutubeMedia {
        if (resolved.audioFormats.all { it.url != null } || playerUrl == null) return resolved
        emit(DownloadEvent.LogEmitted("Protected audio formats detected; fetching player JS from $playerUrl"))
        val directBefore = resolved.audioFormats.count { it.url != null }
        val playerJs = getText(
            playerUrl,
            mapOf(
                "User-Agent" to YoutubeExtractorCommons.BROWSER_USER_AGENT,
                "Accept-Encoding" to "gzip, deflate",
            )
        )
        val solvedFormats = PlayerJsDecipherer.resolveProtectedFormats(resolved.audioFormats, playerJs)
        val directAfter = solvedFormats.count { it.url != null }
        emit(DownloadEvent.LogEmitted("Native signature solver resolved ${directAfter - directBefore} protected audio format(s)"))
        return resolved.copy(audioFormats = solvedFormats)
    }

    private suspend fun downloadFirstAvailableFormat(
        formats: List<NativeAudioFormat>,
        output: File,
        refererUrl: String,
        emit: suspend (DownloadEvent) -> Unit,
    ): NativeAudioFormat {
        var lastError: Exception? = null
        for (format in formats) {
            runCatching {
                output.delete()
                downloadFile(format, output, refererUrl, emit)
                format
            }.onSuccess { return it }
                .onFailure { error ->
                    lastError = error as? Exception ?: IllegalStateException(error.message ?: error.toString())
                    emit(DownloadEvent.LogEmitted("Format ${format.describe()} was rejected, trying fallback: ${error.message}"))
                }
        }
        throw lastError ?: IllegalStateException("No audio format could be downloaded")
    }

    private suspend fun downloadFile(
        format: NativeAudioFormat,
        output: File,
        refererUrl: String,
        emit: suspend (DownloadEvent) -> Unit,
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
                out.flush()
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
    }

    private suspend fun getText(url: String, headers: Map<String, String>): String {
        val response = requestBytes(url, HttpMethod.Get, headers, null)
        if (response.statusCode !in 200..299) throw IllegalStateException("Watch page returned HTTP ${response.statusCode}")
        return response.body.toString(response.charset)
    }

    private suspend fun selectAndroidPlayableManifestUrl(
        masterManifestUrl: String,
        headers: Map<String, String>,
        emit: suspend (DownloadEvent) -> Unit,
    ): String {
        val manifestText = runCatching { getText(masterManifestUrl, headers) }
            .getOrElse { error ->
                emit(DownloadEvent.LogEmitted("Android manifest fetch failed, using original URL: ${error.message}"))
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
            emit(DownloadEvent.LogEmitted("Android manifest parser found no explicit audio playlists; using master manifest"))
            return masterManifestUrl
        }

        val selected = audioPlaylists.maxByOrNull { url ->
            Regex("""/itag/(\d+)/""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        } ?: audioPlaylists.first()

        emit(DownloadEvent.LogEmitted("Android manifest parser selected audio playlist URL"))
        return selected
    }

    private suspend fun downloadHlsAudioToLocalFile(
        manifestUrl: String,
        headers: Map<String, String>,
        stagingDirectory: File,
        emit: suspend (DownloadEvent) -> Unit,
    ): File {
        val playlistText = getText(manifestUrl, headers)
        val lines = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

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

        val outputFile = File(stagingDirectory, if (initSegment != null) "audio-hls.mp4" else "audio-hls.aac")
        emit(DownloadEvent.LogEmitted("Android HLS downloader segments=${segmentUrls.size} parallelism=${minOf(HLS_DOWNLOAD_PARALLELISM, segmentUrls.size)}"))
        outputFile.parentFile?.mkdirs()
        var strippedId3Logged = false
        var totalWritten = 0L
        outputFile.outputStream().use { out ->
            initSegment?.let { url ->
                emit(DownloadEvent.LogEmitted("Android HLS downloader writing init segment"))
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
                    val bytes = if (initSegment == null) HlsAudioSegments.stripLeadingId3Tags(originalBytes) else originalBytes
                    if (!strippedId3Logged && bytes.size != originalBytes.size) {
                        strippedId3Logged = true
                        emit(DownloadEvent.LogEmitted("Android HLS downloader stripped leading ID3 metadata from AAC segments"))
                    }
                    out.write(bytes)
                    totalWritten += bytes.size
                    completedSegments++
                    val percent = (((completedSegments).toDouble() / segmentUrls.size) * 100).roundToInt().coerceIn(0, 100)
                    if (completedSegments == 1 || completedSegments == segmentUrls.size || percent % 10 == 0) {
                        emit(DownloadEvent.LogEmitted("Android HLS downloader progress: segment $completedSegments/${segmentUrls.size}"))
                    }
                    emit(
                        DownloadEvent.ProgressChanged(
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
        emit(DownloadEvent.LogEmitted("Android HLS downloader wrote local fragment file: ${outputFile.absolutePath}"))
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

    private suspend fun requestBytes(url: String, method: HttpMethod, headers: Map<String, String>, body: ByteArray?): ByteResponse {
        val response = client.request(url) {
            this.method = method
            headers { headers.forEach { (key, value) -> append(key, value) } }
            body?.let { setBody(it) }
        }
        return ByteResponse(
            statusCode = response.status.value,
            body = decodeResponseBody(response.bodyAsBytes(), response.headers["Content-Encoding"]),
            charset = charsetFromContentType(response.headers["Content-Type"]),
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
        return charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
    }

    private fun resolveOutputFile(options: DownloadOptions, title: String): File {
        val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim().take(120).ifBlank { "youtube-audio" }
        val defaultFileName = "$safeTitle.mp3"
        val outputTarget = options.outputPath

        if (outputTarget.isNullOrBlank()) return uniqueFile(File(context.filesDir, defaultFileName).absoluteFile)

        val raw = File(outputTarget)
        val looksLikeDirectory = outputTarget.endsWith("/") || outputTarget.endsWith("\\") || !raw.name.contains('.')
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

    private fun userAgentForSource(source: String): String {
        return when (source) {
            "ios-player-api" -> YoutubeConstants.IOS_USER_AGENT
            "tv-player-api" -> YoutubeConstants.TV_USER_AGENT
            "android-player-api" -> YoutubeConstants.ANDROID_USER_AGENT
            else -> YoutubeConstants.BROWSER_USER_AGENT
        }
    }
}

private data class ByteResponse(val statusCode: Int, val body: ByteArray, val charset: Charset)

private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> = (this[key] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
private fun JsonObject.objectAt(vararg path: String): JsonObject? {
    var current: kotlinx.serialization.json.JsonElement = this
    for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
    return current as? JsonObject
}
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
private fun NativeAudioFormat.describe(): String = listOfNotNull(ext, averageBitrate?.toInt()?.let { "$it kbps" }, audioCodec, source).joinToString(" | ")

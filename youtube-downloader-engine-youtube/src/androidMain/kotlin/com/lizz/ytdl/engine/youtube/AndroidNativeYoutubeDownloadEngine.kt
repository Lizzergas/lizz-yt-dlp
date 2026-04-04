package com.lizz.ytdl.engine.youtube

import android.content.Context
import com.lizz.ytdl.core.DownloadEvent
import com.lizz.ytdl.core.DownloadOptions
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.core.DownloadResult
import com.lizz.ytdl.core.DownloadStage
import com.lizz.ytdl.core.TransferSnapshot
import com.lizz.ytdl.core.YoutubeDownloadEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
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
    private val transcoder: AndroidMp3Transcoder = AndroidMp3Transcoder(context),
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
            val hlsWorkingFile = File(stagingDirectory, "audio-hls.aac")
            emit(DownloadEvent.StageChanged(DownloadStage.DownloadAudio, "Downloading HLS audio fragments locally"))
            downloadHlsAudioToLocalFile(
                manifestUrl = playableManifestUrl,
                headers = manifestHeaders,
                outputFile = hlsWorkingFile,
                emit = emit,
            )
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

    private fun postPlayerRequest(apiKey: String, videoId: String, client: PlayerClientConfig): JsonObject {
        val body = buildJsonObject {
            put("context", client.context)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val response = requestBytes(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$apiKey",
            method = "POST",
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
        val response = requestStream(
            url = format.url ?: throw IllegalStateException("Missing format url"),
            headers = buildMap {
                put("User-Agent", userAgentForSource(format.source))
                put("Accept-Encoding", "identity")
                put("Accept", "*/*")
                put("Origin", "https://www.youtube.com")
                put("Referer", refererUrl)
                put("Range", "bytes=0-")
                if (format.source == "ios-player-api") {
                    put("X-YouTube-Client-Name", "5")
                    put("X-YouTube-Client-Version", "21.02.3")
                }
            },
        )
        if (response.statusCode !in 200..299) {
            response.stream.close()
            throw IllegalStateException("Native direct download returned HTTP ${response.statusCode} for ${format.describe()}")
        }

        val totalBytes = response.contentLength.takeIf { it > 0L }
        var written = 0L
        response.stream.use { input ->
            output.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    val percent = totalBytes?.let { ((written.toDouble() / it) * 100).roundToInt().coerceIn(0, 100) }
                    emit(
                        DownloadEvent.ProgressChanged(
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
        val response = requestBytes(url, "GET", headers, null)
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
        outputFile: File,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
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

        emit(DownloadEvent.LogEmitted("Android HLS downloader segments=${segmentUrls.size}"))
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { out ->
            initSegment?.let { url ->
                emit(DownloadEvent.LogEmitted("Android HLS downloader writing init segment"))
                val bytes = requestBytes(url, "GET", headers, null).body
                out.write(bytes)
            }

            segmentUrls.forEachIndexed { index, url ->
                val bytes = requestBytes(url, "GET", headers, null).body
                out.write(bytes)
                val percent = (((index + 1).toDouble() / segmentUrls.size) * 100).roundToInt().coerceIn(0, 100)
                if (index == 0 || (index + 1) == segmentUrls.size || percent % 10 == 0) {
                    emit(DownloadEvent.LogEmitted("Android HLS downloader progress: segment ${index + 1}/${segmentUrls.size}"))
                }
                emit(
                    DownloadEvent.ProgressChanged(
                        TransferSnapshot(
                            label = "Downloading HLS audio fragments",
                            progressPercent = percent,
                            downloadedBytes = outputFile.length(),
                        )
                    )
                )
            }
        }
        emit(DownloadEvent.LogEmitted("Android HLS downloader wrote local fragment file: ${outputFile.absolutePath}"))
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

    private fun requestBytes(url: String, method: String, headers: Map<String, String>, body: ByteArray?): ByteResponse {
        val connection = openConnection(url, method, headers)
        body?.let {
            connection.doOutput = true
            connection.outputStream.use { stream -> stream.write(it) }
        }
        val statusCode = connection.responseCode
        val stream = if (statusCode >= 400) connection.errorStream ?: connection.inputStream else connection.inputStream
        val bytes = stream.use { readAllBytes(it, connection.contentEncoding) }
        val charset = charsetFromContentType(connection.contentType)
        return ByteResponse(statusCode, bytes, charset)
    }

    private fun requestStream(url: String, headers: Map<String, String>): StreamResponse {
        val connection = openConnection(url, "GET", headers)
        val statusCode = connection.responseCode
        val stream = if (statusCode >= 400) connection.errorStream ?: connection.inputStream else connection.inputStream
        return StreamResponse(statusCode, stream, connection.contentLengthLong)
    }

    private fun openConnection(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
    }

    private fun readAllBytes(stream: InputStream, encoding: String?): ByteArray {
        val decoded = when (encoding?.lowercase()) {
            "gzip" -> GZIPInputStream(stream)
            "deflate" -> InflaterInputStream(stream)
            else -> stream
        }
        return decoded.readBytes()
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
            "ios-player-api" -> "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            "tv-player-api" -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
            "android-player-api" -> "com.google.android.youtube/19.09.37 (Linux; U; Android 12; US) gzip"
            else -> YoutubeExtractorCommons.BROWSER_USER_AGENT
        }
    }
}

private data class ByteResponse(val statusCode: Int, val body: ByteArray, val charset: Charset)
private data class StreamResponse(val statusCode: Int, val stream: InputStream, val contentLength: Long)

private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> = (this[key] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
private fun JsonObject.objectAt(vararg path: String): JsonObject? {
    var current: kotlinx.serialization.json.JsonElement = this
    for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
    return current as? JsonObject
}
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
private fun NativeAudioFormat.describe(): String = listOfNotNull(ext, averageBitrate?.toInt()?.let { "$it kbps" }, audioCodec, source).joinToString(" | ")

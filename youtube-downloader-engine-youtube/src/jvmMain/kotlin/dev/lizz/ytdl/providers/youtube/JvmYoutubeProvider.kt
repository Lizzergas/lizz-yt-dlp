package dev.lizz.ytdl.providers.youtube

import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.core.AudioDownloadOptions
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.core.AudioDownloadResult
import dev.lizz.ytdl.core.MediaStage
import dev.lizz.ytdl.core.ProviderId
import dev.lizz.ytdl.core.TranscriptRequest
import dev.lizz.ytdl.core.TranscriptResult
import dev.lizz.ytdl.core.TransferSnapshot
import dev.lizz.ytdl.core.MediaProvider
import dev.lizz.ytdl.providers.youtube.YoutubeProviderSupport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class JvmYoutubeProvider(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val commons: YoutubeExtractorCommons = YoutubeExtractorCommons(),
    private val ffmpegMp3Converter: NativeJvmMp3Converter = NativeJvmMp3Converter(),
) : MediaProvider {
    override val id: ProviderId = YoutubeProviderSupport.providerId

    override fun canHandle(locator: String): Boolean = YoutubeProviderSupport.canHandle(locator)

    override suspend fun downloadAudio(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit,
    ): AudioDownloadResult = withContext(Dispatchers.IO) {
        emit(MediaEvent.StageChanged(MediaStage.PrepareProvider, "Resolving YouTube watch page in native Kotlin engine"))

        val watchPageHtml = getText(
            url = request.url,
            headers = mapOf(
                "User-Agent" to YoutubeExtractorCommons.BROWSER_USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate",
            ),
        )

        emit(MediaEvent.StageChanged(MediaStage.ProbeMetadata, "Parsing watch page and requesting Innertube player responses"))
        val watchData = commons.parseWatchData(request.url, watchPageHtml)
        val apiPlayers = callPlayerApis(watchData.videoId, watchData.ytcfg, emit)
        val initialResolved = commons.resolveMedia(request.url, watchData.initialPlayerResponse, apiPlayers)
        val resolved = attemptProtectedFormatResolution(initialResolved, watchData.playerUrl, emit)
        emit(MediaEvent.MetadataResolved(resolved.metadata))

        val stagingDirectory = Files.createTempDirectory("kt-ytdlp-native-")
        val selectedFormat = commons.pickBestAudioFormat(resolved.audioFormats)
        val orderedFormats = (listOf(selectedFormat) + resolved.audioFormats.filterNot { it.url == selectedFormat.url })
            .filter { it.url != null }

        emit(MediaEvent.LogEmitted("Native engine selected ${selectedFormat.describe()}") )

        val workingFile = stagingDirectory.resolve("audio.${selectedFormat.ext}")
        val outputFile = resolveOutputPath(request.options, resolved.metadata.title)
        emit(MediaEvent.OutputResolved(outputFile.toString()))

        emit(MediaEvent.StageChanged(MediaStage.DownloadAudio, "Downloading direct audio stream resolved by native engine"))
        val directDownloadSucceeded = runCatching {
            val downloadedFormat = downloadFirstAvailableFormat(orderedFormats, workingFile, watchData.canonicalUrl, emit)
            emit(MediaEvent.WorkingFileResolved(workingFile.toString()))
            emit(MediaEvent.LogEmitted("Downloaded using ${downloadedFormat.describe()}"))

            emit(MediaEvent.StageChanged(MediaStage.ConvertOutput, "Transcoding the downloaded audio stream into mp3"))
            ffmpegMp3Converter.convert(
                inputFile = workingFile,
                outputFile = outputFile,
                durationSeconds = resolved.metadata.durationSeconds,
                emit = emit,
            )
            true
        }.getOrElse { error ->
            emit(MediaEvent.LogEmitted("Direct media URLs were not usable: ${error.message}"))
            false
        }

        if (!directDownloadSucceeded) {
            val manifest = resolved.dashManifestUrls.firstOrNull() ?: resolved.hlsManifestUrls.firstOrNull()
                ?: throw IllegalStateException("Direct download failed and no HLS manifest fallback was available")
            emit(MediaEvent.LogEmitted("Falling back to ${manifest.kind.uppercase()} manifest from ${manifest.source}"))
            emit(MediaEvent.StageChanged(MediaStage.ConvertOutput, "Downloading and transcoding audio from ${manifest.kind.uppercase()} manifest"))
            ffmpegMp3Converter.convertFromManifest(
                manifestUrl = manifest.url,
                outputFile = outputFile,
                refererUrl = watchData.canonicalUrl,
                userAgent = userAgentForSource(manifest.source),
                durationSeconds = resolved.metadata.durationSeconds,
                emit = emit,
            )
        }

        emit(MediaEvent.StageChanged(MediaStage.Finalize, "Cleaning temporary files and finalizing output"))
        Files.deleteIfExists(workingFile)
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

    override suspend fun getTranscriptCues(request: TranscriptRequest): TranscriptResult? = withContext(Dispatchers.IO) {
        if (!request.languageCode.startsWith("en", ignoreCase = true)) return@withContext null
        val watchPageHtml = getText(
            url = request.url,
            headers = mapOf(
                "User-Agent" to YoutubeExtractorCommons.BROWSER_USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate",
            ),
        )
        val watchData = commons.parseWatchData(request.url, watchPageHtml)
        val apiPlayers = callPlayerApis(watchData.videoId, watchData.ytcfg, emit = {})
        val track = YoutubeTranscriptResolver.resolvePreferredTrack(watchData.initialPlayerResponse, apiPlayers, request.languageCode) ?: return@withContext null
        val vttText = getText(
            url = buildTranscriptVttUrl(track.baseUrl),
            headers = mapOf(
                "User-Agent" to userAgentForSource(track.source),
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate",
                "Origin" to "https://www.youtube.com",
                "Referer" to watchData.canonicalUrl,
            ),
        )
        YoutubeWebVttParser.parse(vttText, track.languageCode, track.isAutoGenerated, track.source)
    }

    private suspend fun callPlayerApis(
        videoId: String,
        ytcfg: JsonObject,
        emit: suspend (MediaEvent) -> Unit,
    ): List<Pair<String, JsonObject>> {
        val apiKey = ytcfg.string("INNERTUBE_API_KEY")
            ?: throw IllegalStateException("Watch page is missing INNERTUBE_API_KEY")

        val clients = commons.createInnertubeClients(ytcfg)
        val results = mutableListOf<Pair<String, JsonObject>>()

        for (client in clients) {
            try {
                val player = postPlayerRequest(apiKey, videoId, client)
                emit(MediaEvent.LogEmitted("${client.label} responded with ${summarize(player)}"))
                results += client.label to player
            } catch (e: Exception) {
                emit(MediaEvent.LogEmitted("${client.label} unavailable: ${e.message}"))
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
        }

        val request = HttpRequest.newBuilder(URI("https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$apiKey"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.headerClientName)
            .header("X-YouTube-Client-Version", client.headerClientVersion)
            .header("Accept-Encoding", "gzip, deflate")
            .header("Origin", "https://www.youtube.com")
            .apply {
                if (client.visitorData != null) {
                    header("X-Goog-Visitor-Id", client.visitorData)
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build()

        val response = sendBytes(request)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()}")
        }
        return kotlinx.serialization.json.Json.parseToJsonElement(decodeBody(response).toString(StandardCharsets.UTF_8)) as JsonObject
    }

    private suspend fun attemptProtectedFormatResolution(
        resolved: ResolvedYoutubeMedia,
        playerUrl: String?,
        emit: suspend (MediaEvent) -> Unit,
    ): ResolvedYoutubeMedia {
        if (resolved.audioFormats.all { it.url != null } || playerUrl == null) return resolved

        emit(MediaEvent.LogEmitted("Protected audio formats detected; fetching player JS from $playerUrl"))
        val directBefore = resolved.audioFormats.count { it.url != null }
        val playerJs = getText(
            playerUrl,
            headers = mapOf(
                "User-Agent" to YoutubeExtractorCommons.BROWSER_USER_AGENT,
                "Accept-Encoding" to "gzip, deflate",
            ),
        )
        val solvedFormats = PlayerJsDecipherer.resolveProtectedFormats(resolved.audioFormats, playerJs)
        val directAfter = solvedFormats.count { it.url != null }
        emit(MediaEvent.LogEmitted("Native signature solver resolved ${directAfter - directBefore} protected audio format(s)"))
        return resolved.copy(audioFormats = solvedFormats)
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
                 lastError = error as? Exception ?: IllegalStateException(error.message ?: error.toString())
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
            .header("User-Agent", userAgentForFormat(format))
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

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1).takeIf { it >= 0L }
        var written = 0L

        response.body().use { input ->
            Files.newOutputStream(output).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    val percent = totalBytes?.let { ((written.toDouble() / it) * 100).roundToInt().coerceIn(0, 100) }
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
        val charset = charsetFromContentType(response.headers().firstValue("Content-Type").orElse(null))
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
        return charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
    }

    private fun resolveOutputPath(options: AudioDownloadOptions, title: String): Path {
        val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("""\s+"""), " ").trim().take(120).ifBlank { "youtube-audio" }
        val defaultFileName = "$safeTitle.mp3"
        val outputTarget = options.outputPath

        if (outputTarget.isNullOrBlank()) return uniquePath(Path.of(defaultFileName).toAbsolutePath())

        val rawPath = Path.of(outputTarget)
        val looksLikeDirectory = outputTarget.endsWith("/") || outputTarget.endsWith("\\") || !rawPath.fileName.toString().contains('.')
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

    private fun userAgentForFormat(format: NativeAudioFormat): String {
        return userAgentForSource(format.source)
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

internal class NativeJvmMp3Converter {
    suspend fun convert(
        inputFile: Path,
        outputFile: Path,
        durationSeconds: Int?,
        emit: suspend (MediaEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        outputFile.toAbsolutePath().parent?.let(Files::createDirectories)
        val process = ProcessBuilder(
            listOf(
                "ffmpeg", "-y", "-i", inputFile.toString(), "-vn", "-map_metadata", "0",
                "-codec:a", "libmp3lame", "-q:a", "0", "-progress", "pipe:1", "-nostats", outputFile.toString()
            )
        ).redirectErrorStream(true).start()

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
                            durationSeconds != null && durationSeconds > 0 && outTimeUs != null -> ((outTimeUs!!.toDouble() / 1_000_000.0 / durationSeconds) * 100).roundToInt().coerceIn(0, 100)
                            else -> null
                        }
                        emit(MediaEvent.ProgressChanged(TransferSnapshot(label = "Converting audio to mp3", progressPercent = percent, speedText = speed)))
                    }
                }
            }
        }

        val exit = process.waitFor()
        if (exit != 0) throw IllegalStateException("ffmpeg failed with exit code $exit")
    }

    suspend fun convertFromManifest(
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

        val process = ProcessBuilder(
            listOf(
                "ffmpeg", "-y", "-user_agent", userAgent, "-headers", headers,
                "-i", manifestUrl, "-vn", "-map_metadata", "0", "-codec:a", "libmp3lame",
                "-q:a", "0", "-progress", "pipe:1", "-nostats", outputFile.toString()
            )
        ).redirectErrorStream(true).start()

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
                                    label = "Downloading audio from manifest",
                                    progressPercent = percent,
                                    speedText = speed,
                                )
                            )
                        )
                    }
                }
            }
        }

        val exit = process.waitFor()
        if (exit != 0) throw IllegalStateException("ffmpeg manifest download failed with exit code $exit")
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> = (this[key] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
private fun JsonObject.objectAt(vararg path: String): JsonObject? {
    var current: kotlinx.serialization.json.JsonElement = this
    for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
    return current as? JsonObject
}
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
private fun NativeAudioFormat.describe(): String = listOfNotNull(ext, averageBitrate?.toInt()?.let { "$it kbps" }, audioCodec, source).joinToString(" | ")

package com.lizz.ytdl.engine.youtube

import com.lizz.ytdl.core.VideoMetadata
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class NativeAudioFormat(
    val url: String?,
    val formatId: String?,
    val ext: String,
    val mimeType: String?,
    val audioCodec: String?,
    val averageBitrate: Double?,
    val source: String,
    val signatureCipher: String? = null,
)

internal data class ExtractedWatchData(
    val videoId: String,
    val canonicalUrl: String,
    val ytcfg: JsonObject,
    val initialPlayerResponse: JsonObject?,
    val playerUrl: String?,
)

internal data class ResolvedYoutubeMedia(
    val metadata: VideoMetadata,
    val audioFormats: List<NativeAudioFormat>,
    val hlsManifestUrls: List<NativeManifest>,
)

internal data class NativeManifest(
    val url: String,
    val source: String,
    val kind: String,
)

internal class YoutubeExtractorCommons {
    fun parseYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|youtu\.be/|/embed/|/shorts/|/live/)([0-9A-Za-z_-]{11})"""),
            Regex("""^([0-9A-Za-z_-]{11})$"""),
        )
        return patterns.asSequence().mapNotNull { regex -> regex.find(url)?.groupValues?.getOrNull(1) }.firstOrNull()
    }

    fun parseWatchData(url: String, watchPageHtml: String): ExtractedWatchData {
        val videoId = parseYoutubeId(url)
            ?: throw IllegalStateException("Unsupported YouTube URL or video id: $url")
        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        val ytcfg = extractJsonObjectAfter(watchPageHtml, listOf("ytcfg.set({", "window.ytcfg.set({"))
            ?: throw IllegalStateException("Could not extract ytcfg from watch page")
        val initialPlayer = extractJsonObjectAfter(
            watchPageHtml,
            listOf("var ytInitialPlayerResponse =", "ytInitialPlayerResponse =", "window.ytInitialPlayerResponse =")
        )
        val playerUrl = extractPlayerUrl(watchPageHtml, ytcfg)
        return ExtractedWatchData(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            ytcfg = ytcfg,
            initialPlayerResponse = initialPlayer,
            playerUrl = playerUrl,
        )
    }

    fun resolveMedia(
        originalUrl: String,
        initialPlayerResponse: JsonObject?,
        playerResponses: List<Pair<String, JsonObject>>,
    ): ResolvedYoutubeMedia {
        val formats = buildList {
            playerResponses.forEach { (source, player) ->
                addAll(extractAudioFormats(player, source))
            }
            addAll(extractAudioFormats(initialPlayerResponse, "watch-page"))
        }.distinctBy { it.url ?: it.signatureCipher ?: it.formatId ?: it.source }

        val manifests = buildList {
            playerResponses.forEach { (source, player) ->
                addAll(extractManifests(player, source))
            }
            addAll(extractManifests(initialPlayerResponse, "watch-page"))
        }.distinctBy { it.url }

        if (formats.none { it.url != null }) {
            if (manifests.isEmpty()) {
                throw IllegalStateException(
                    "Native engine could not find any direct audio URLs or manifests. This video likely requires signature or n-parameter solving that is not implemented yet."
                )
            }
        }

        val title = listOfNotNull(
            playerResponses.firstNotNullOfOrNull { (_, player) -> player.stringAt("videoDetails", "title") },
            initialPlayerResponse?.stringAt("videoDetails", "title"),
        ).firstOrNull().orEmpty().ifBlank { parseYoutubeId(originalUrl) ?: "youtube-audio" }

        val uploader = listOfNotNull(
            playerResponses.firstNotNullOfOrNull { (_, player) -> player.stringAt("videoDetails", "author") },
            initialPlayerResponse?.stringAt("videoDetails", "author"),
        ).firstOrNull()

        val durationSeconds = listOfNotNull(
            playerResponses.firstNotNullOfOrNull { (_, player) -> player.stringAt("videoDetails", "lengthSeconds")?.toIntOrNull() },
            initialPlayerResponse?.stringAt("videoDetails", "lengthSeconds")?.toIntOrNull(),
        ).firstOrNull()

        val best = formats.maxByOrNull { it.averageBitrate ?: 0.0 }

        return ResolvedYoutubeMedia(
            metadata = VideoMetadata(
                title = title,
                uploader = uploader,
                durationSeconds = durationSeconds,
                durationText = durationSeconds?.let(::formatDuration),
                sourceUrl = originalUrl,
                availableAudioFormats = formats.size,
                bestAudioDescription = best?.describe(),
            ),
            audioFormats = formats.sortedByDescending { it.averageBitrate ?: 0.0 },
            hlsManifestUrls = manifests.filter { it.kind == "hls" },
        )
    }

    fun pickBestAudioFormat(formats: List<NativeAudioFormat>): NativeAudioFormat {
        return formats.filter { it.url != null }.maxWithOrNull(
            compareBy<NativeAudioFormat>(
                { if (it.ext == "m4a") 1 else 0 },
                { it.averageBitrate ?: -1.0 },
            )
        ) ?: throw IllegalStateException("No usable audio format found")
    }

    fun createInnertubeClients(ytcfg: JsonObject): List<PlayerClientConfig> {
        val visitorData = ytcfg.string("VISITOR_DATA")
            ?: ytcfg.stringAt("INNERTUBE_CONTEXT", "client", "visitorData")
        val webContext = ytcfg.objectAt("INNERTUBE_CONTEXT")

        return buildList {
            if (webContext != null) {
                add(
                    PlayerClientConfig(
                        label = "web-player-api",
                        context = webContext,
                        headerClientName = ytcfg.string("INNERTUBE_CONTEXT_CLIENT_NAME") ?: "1",
                        headerClientVersion = ytcfg.string("INNERTUBE_CONTEXT_CLIENT_VERSION") ?: "2.20260403.01.00",
                        userAgent = ytcfg.stringAt("INNERTUBE_CONTEXT", "client", "userAgent") ?: BROWSER_USER_AGENT,
                        visitorData = visitorData,
                    )
                )
            }
            add(
                PlayerClientConfig(
                    label = "ios-player-api",
                    context = buildJsonObjectString(
                        mapOf(
                            "client" to mapOf(
                                "clientName" to "IOS",
                                "clientVersion" to IOS_CLIENT_VERSION,
                                "deviceMake" to "Apple",
                                "deviceModel" to "iPhone16,2",
                                "userAgent" to IOS_USER_AGENT,
                                "osName" to "iPhone",
                                "osVersion" to "18.3.2.22D82",
                                "hl" to "en",
                                "gl" to "US",
                                "visitorData" to visitorData,
                            )
                        )
                    ),
                    headerClientName = "5",
                    headerClientVersion = IOS_CLIENT_VERSION,
                    userAgent = IOS_USER_AGENT,
                    visitorData = visitorData,
                )
            )
            add(
                PlayerClientConfig(
                    label = "tv-player-api",
                    context = buildJsonObjectString(
                        mapOf(
                            "client" to mapOf(
                                "clientName" to "TVHTML5",
                                "clientVersion" to TV_CLIENT_VERSION,
                                "userAgent" to TV_USER_AGENT,
                                "visitorData" to visitorData,
                            )
                        )
                    ),
                    headerClientName = "7",
                    headerClientVersion = TV_CLIENT_VERSION,
                    userAgent = TV_USER_AGENT,
                    visitorData = visitorData,
                )
            )
            add(
                PlayerClientConfig(
                    label = "android-player-api",
                    context = buildJsonObjectString(
                        mapOf(
                            "client" to mapOf(
                                "clientName" to "ANDROID",
                                "clientVersion" to ANDROID_CLIENT_VERSION,
                                "androidSdkVersion" to 31,
                                "hl" to "en",
                                "gl" to "US",
                                "osName" to "Android",
                                "osVersion" to "12",
                                "platform" to "MOBILE",
                                "timeZone" to "UTC",
                                "utcOffsetMinutes" to 0,
                                "userAgent" to ANDROID_USER_AGENT,
                                "visitorData" to visitorData,
                            )
                        )
                    ),
                    headerClientName = "3",
                    headerClientVersion = ANDROID_CLIENT_VERSION,
                    userAgent = ANDROID_USER_AGENT,
                    visitorData = visitorData,
                )
            )
        }
    }

    private fun extractAudioFormats(player: JsonObject?, source: String): List<NativeAudioFormat> {
        val streamingData = player?.objectAt("streamingData") ?: return emptyList()
        val rawFormats = buildList {
            addAll(streamingData.array("formats"))
            addAll(streamingData.array("adaptiveFormats"))
        }

        return rawFormats.mapNotNull { item ->
            val format = item as? JsonObject ?: return@mapNotNull null
            val directUrl = format.string("url")
            val rawCipher = format.string("signatureCipher")
            if (directUrl == null && rawCipher == null) return@mapNotNull null
            val mimeType = format.string("mimeType")
            val codecs = mimeType
                ?.substringAfter("codecs=\"", "")
                ?.substringBefore('"')
                ?.split(',')
                ?.map { it.trim() }
                .orEmpty()
            val acodec = codecs.firstOrNull { codec ->
                codec.startsWith("mp4a") || codec.startsWith("opus") || codec.startsWith("vorbis")
            } ?: if (mimeType?.startsWith("audio/") == true) mimeType.substringAfter('/') else null

            if (acodec == null) return@mapNotNull null

            val ext = mimeType
                ?.substringBefore(';')
                ?.substringAfter('/')
                ?.ifBlank { null }
                ?: directUrl?.substringAfterLast('.', "bin")?.substringBefore('?')?.substringBefore('#')
                ?: "bin"

            NativeAudioFormat(
                url = directUrl,
                formatId = format.string("itag"),
                ext = ext,
                mimeType = mimeType,
                audioCodec = acodec,
                averageBitrate = format.double("abr") ?: format.double("bitrate")?.let { it / 1000.0 },
                source = source,
                signatureCipher = rawCipher,
            )
        }
    }

    private fun extractPlayerUrl(source: String, ytcfg: JsonObject): String? {
        val direct = ytcfg.string("PLAYER_JS_URL")
            ?: ytcfg.stringAt("WEB_PLAYER_CONTEXT_CONFIGS", "WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH", "jsUrl")
            ?: ytcfg.stringAt("WEB_PLAYER_CONTEXT_CONFIGS", "jsUrl")
        if (direct != null) return normalizePlayerUrl(direct)

        val regexes = listOf(
            Regex("""<link[^>]+href="([^"]*/s/player/[^"]+/base\.js)"""),
            Regex(""""jsUrl":"([^"]*/s/player/[^"]+/base\.js)"""),
        )
        return regexes.firstNotNullOfOrNull { it.find(source)?.groupValues?.getOrNull(1) }
            ?.let(::normalizePlayerUrl)
    }

    private fun normalizePlayerUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "https://www.youtube.com$url"
        }
    }

    private fun extractManifests(player: JsonObject?, source: String): List<NativeManifest> {
        val streamingData = player?.objectAt("streamingData") ?: return emptyList()
        return buildList {
            streamingData.string("hlsManifestUrl")?.let {
                add(NativeManifest(url = it, source = source, kind = "hls"))
            }
            streamingData.string("dashManifestUrl")?.let {
                add(NativeManifest(url = it, source = source, kind = "dash"))
            }
        }
    }

    private fun extractJsonObjectAfter(source: String, markers: List<String>): JsonObject? {
        for (marker in markers) {
            val markerIndex = source.indexOf(marker)
            if (markerIndex < 0) continue
            val objectStart = source.indexOf('{', markerIndex)
            if (objectStart < 0) continue
            val objectText = readBalancedJsonObject(source, objectStart) ?: continue
            return runCatching { Json.parseToJsonElement(objectText) as? JsonObject }.getOrNull()
        }
        return null
    }

    private fun readBalancedJsonObject(text: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in startIndex until text.length) {
            val ch = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(startIndex, index + 1)
                }
            }
        }
        return null
    }

    private fun buildJsonObjectString(map: Map<String, Any?>): JsonObject {
        return Json.parseToJsonElement(serializeValue(map)) as JsonObject
    }

    private fun serializeValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> Json.encodeToString(String.serializer(), value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                Json.encodeToString(String.serializer(), k.toString()) + ":" + serializeValue(v)
            }
            else -> error("Unsupported JSON literal value: $value")
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
        } else {
            "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
        }
    }

    private fun NativeAudioFormat.describe(): String {
        return listOfNotNull(ext, averageBitrate?.toInt()?.let { "$it kbps" }, audioCodec, source).joinToString(" | ")
    }

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        private const val ANDROID_CLIENT_VERSION = "19.09.37"
        private const val ANDROID_USER_AGENT =
            "com.google.android.youtube/19.09.37 (Linux; U; Android 12; US) gzip"

        private const val IOS_CLIENT_VERSION = "21.02.3"
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

        private const val TV_CLIENT_VERSION = "7.20260114.12.00"
        private const val TV_USER_AGENT =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
    }
}

internal data class PlayerClientConfig(
    val label: String,
    val context: JsonObject,
    val headerClientName: String,
    val headerClientVersion: String,
    val userAgent: String,
    val visitorData: String?,
)

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()
private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList().orEmpty()
private fun JsonObject.objectAt(vararg path: String): JsonObject? {
    var current: JsonElement = this
    for (key in path) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current as? JsonObject
}
private fun JsonObject.stringAt(vararg path: String): String? {
    var current: JsonElement = this
    for (key in path) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return (current as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
}
private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

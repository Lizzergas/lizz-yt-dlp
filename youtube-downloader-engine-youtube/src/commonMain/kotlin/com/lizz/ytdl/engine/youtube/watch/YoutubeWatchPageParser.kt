package com.lizz.ytdl.engine.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object YoutubeWatchPageParser {
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
        return ExtractedWatchData(videoId, canonicalUrl, ytcfg, initialPlayer, playerUrl)
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
        return regexes.firstNotNullOfOrNull { it.find(source)?.groupValues?.getOrNull(1) }?.let(::normalizePlayerUrl)
    }

    private fun normalizePlayerUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "https://www.youtube.com$url"
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
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
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
}

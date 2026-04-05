package dev.lizz.ytdl.providers.youtube.probe

import dev.lizz.ytdl.providers.youtube.YoutubeConstants
import dev.lizz.ytdl.providers.youtube.YoutubeExtractorCommons
import dev.lizz.ytdl.providers.youtube.array
import dev.lizz.ytdl.providers.youtube.objectAt
import dev.lizz.ytdl.providers.youtube.string
import kotlinx.serialization.json.JsonObject

internal fun defaultYoutubeTextHeaders(context: YoutubeRequestContext = YoutubeRequestContext()): Map<String, String> {
    return mapOf(
        "User-Agent" to YoutubeExtractorCommons.BROWSER_USER_AGENT,
        "Accept-Language" to context.acceptLanguage,
        "Accept-Encoding" to "gzip, deflate",
    )
}

internal fun userAgentForSource(source: String): String {
    return when (source) {
        "ios-player-api" -> YoutubeConstants.IOS_USER_AGENT
        "tv-player-api" -> YoutubeConstants.TV_USER_AGENT
        "android-player-api" -> YoutubeConstants.ANDROID_USER_AGENT
        else -> YoutubeConstants.BROWSER_USER_AGENT
    }
}

internal fun summarize(player: JsonObject): String {
    val streamingData = player.objectAt("streamingData") ?: return "no streamingData"
    val allFormats = buildList {
        addAll(streamingData.array("formats"))
        addAll(streamingData.array("adaptiveFormats"))
    }
    val direct = allFormats.count { (it as? JsonObject)?.string("url") != null }
    val cipher = allFormats.count { (it as? JsonObject)?.string("signatureCipher") != null }
    return "direct=$direct cipher=$cipher"
}

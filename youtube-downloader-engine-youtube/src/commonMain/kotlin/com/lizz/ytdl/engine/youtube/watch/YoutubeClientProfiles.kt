package com.lizz.ytdl.engine.youtube

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object YoutubeClientProfiles {
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
                        userAgent = ytcfg.stringAt("INNERTUBE_CONTEXT", "client", "userAgent") ?: YoutubeConstants.BROWSER_USER_AGENT,
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
                                "clientVersion" to YoutubeConstants.IOS_CLIENT_VERSION,
                                "deviceMake" to "Apple",
                                "deviceModel" to "iPhone16,2",
                                "userAgent" to YoutubeConstants.IOS_USER_AGENT,
                                "osName" to "iPhone",
                                "osVersion" to "18.3.2.22D82",
                                "hl" to "en",
                                "gl" to "US",
                                "visitorData" to visitorData,
                            )
                        )
                    ),
                    headerClientName = "5",
                    headerClientVersion = YoutubeConstants.IOS_CLIENT_VERSION,
                    userAgent = YoutubeConstants.IOS_USER_AGENT,
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
                                "clientVersion" to YoutubeConstants.TV_CLIENT_VERSION,
                                "userAgent" to YoutubeConstants.TV_USER_AGENT,
                                "visitorData" to visitorData,
                            )
                        )
                    ),
                    headerClientName = "7",
                    headerClientVersion = YoutubeConstants.TV_CLIENT_VERSION,
                    userAgent = YoutubeConstants.TV_USER_AGENT,
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
                                "clientVersion" to YoutubeConstants.ANDROID_CLIENT_VERSION,
                                "androidSdkVersion" to 31,
                                "hl" to "en",
                                "gl" to "US",
                                "osName" to "Android",
                                "osVersion" to "12",
                                "platform" to "MOBILE",
                                "timeZone" to "UTC",
                                "utcOffsetMinutes" to 0,
                                "userAgent" to YoutubeConstants.ANDROID_USER_AGENT,
                                "visitorData" to visitorData,
                            )
                        )
                    ),
                    headerClientName = "3",
                    headerClientVersion = YoutubeConstants.ANDROID_CLIENT_VERSION,
                    userAgent = YoutubeConstants.ANDROID_USER_AGENT,
                    visitorData = visitorData,
                )
            )
        }
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
}

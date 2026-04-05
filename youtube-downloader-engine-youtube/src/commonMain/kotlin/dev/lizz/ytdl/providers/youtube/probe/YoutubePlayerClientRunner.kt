package dev.lizz.ytdl.providers.youtube.probe

import dev.lizz.ytdl.core.MediaEvent
import dev.lizz.ytdl.providers.youtube.YoutubeExtractorCommons
import dev.lizz.ytdl.providers.youtube.string
import dev.lizz.ytdl.providers.youtube.net.YoutubeResponseCache
import dev.lizz.ytdl.providers.youtube.net.YoutubeRetryPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class YoutubePlayerClientRunner(
    private val commons: YoutubeExtractorCommons = YoutubeExtractorCommons(),
    private val cache: YoutubeResponseCache = YoutubeResponseCache(),
) {
    suspend fun callPlayerApis(
        videoId: String,
        ytcfg: JsonObject,
        transport: YoutubeProbeTransport,
        emit: suspend (MediaEvent) -> Unit,
    ): List<Pair<String, JsonObject>> = coroutineScope {
        val apiKey = ytcfg.string("INNERTUBE_API_KEY")
            ?: throw IllegalStateException("Watch page is missing INNERTUBE_API_KEY")

        val clients = commons.createInnertubeClients(ytcfg)
        clients.map { client ->
            async {
                runCatching {
                    val player = cache.getOrLoad("player:$videoId:${client.label}") {
                        YoutubeRetryPolicy.run {
                            transport.postJson(
                                url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$apiKey",
                                headers = buildMap {
                                    put("Content-Type", "application/json")
                                    put("User-Agent", client.userAgent)
                                    put("X-YouTube-Client-Name", client.headerClientName)
                                    put("X-YouTube-Client-Version", client.headerClientVersion)
                                    put("Accept-Encoding", "gzip, deflate")
                                    put("Origin", "https://www.youtube.com")
                                    client.visitorData?.let { put("X-Goog-Visitor-Id", it) }
                                },
                                body = buildJsonObject {
                                    put("context", client.context)
                                    put("videoId", videoId)
                                    put("contentCheckOk", true)
                                    put("racyCheckOk", true)
                                },
                            )
                        }
                    }
                    emit(MediaEvent.LogEmitted("${client.label} responded with ${summarize(player)}"))
                    client.label to player
                }.getOrElse { error ->
                    emit(MediaEvent.LogEmitted("${client.label} unavailable: ${error.message}"))
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
}

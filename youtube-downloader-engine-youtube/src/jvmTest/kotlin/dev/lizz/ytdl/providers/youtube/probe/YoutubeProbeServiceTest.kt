package dev.lizz.ytdl.providers.youtube.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class YoutubeProbeServiceTest {
    @Test
    fun `reuses cached probe results for repeated requests`() = runBlocking {
        val service = YoutubeProbeService()
        val transport = FakeProbeTransport(
            watchPageHtml = resourceText("fixtures/youtube/watch/sample-watch.html"),
            playerResponse = buildJsonObject { },
        )

        service.probe("https://www.youtube.com/watch?v=dQw4w9WgXcQ", transport)
        service.probe("https://www.youtube.com/watch?v=dQw4w9WgXcQ", transport)

        assertEquals(1, transport.watchFetchCount)
        assertEquals(4, transport.playerPostCount)
    }

    @Test
    fun `probe tolerates partial player client failures`() = runBlocking {
        val service = YoutubeProbeService()
        val transport = FakeProbeTransport(
            watchPageHtml = resourceText("fixtures/youtube/watch/sample-watch.html"),
            playerResponse = buildJsonObject { },
            failingClientNames = setOf("1", "5"),
        )

        val probe = service.probe("https://www.youtube.com/watch?v=dQw4w9WgXcQ", transport)

        assertEquals(2, probe.playerResponses.size)
        assertEquals("Sample Video", probe.resolvedMedia.metadata.title)
    }

    private fun resourceText(path: String): String {
        return requireNotNull(this::class.java.classLoader.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }.bufferedReader().readText()
    }
}

private class FakeProbeTransport(
    private val watchPageHtml: String,
    private val playerResponse: JsonObject,
    private val failingClientNames: Set<String> = emptySet(),
) : YoutubeProbeTransport {
    var watchFetchCount: Int = 0
        private set
    var playerPostCount: Int = 0
        private set

    override suspend fun getText(url: String, headers: Map<String, String>): String {
        watchFetchCount++
        return watchPageHtml
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): JsonObject {
        playerPostCount++
        if (headers["X-YouTube-Client-Name"] in failingClientNames) {
            throw IllegalStateException("HTTP 500")
        }
        return playerResponse
    }
}

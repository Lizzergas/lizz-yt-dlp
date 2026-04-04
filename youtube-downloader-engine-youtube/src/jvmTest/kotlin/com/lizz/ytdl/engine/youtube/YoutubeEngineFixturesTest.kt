package com.lizz.ytdl.engine.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YoutubeEngineFixturesTest {
    @Test
    fun parsesWatchPageFixture() {
        val html = resourceText("fixtures/youtube/watch/sample-watch.html")
        val parsed = YoutubeWatchPageParser.parseWatchData("https://www.youtube.com/watch?v=dQw4w9WgXcQ", html)

        assertEquals("dQw4w9WgXcQ", parsed.videoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", parsed.canonicalUrl)
        assertEquals("abc123", parsed.ytcfg.string("INNERTUBE_API_KEY"))
        assertEquals("https://www.youtube.com/s/player/test/base.js", parsed.playerUrl)
        assertEquals("Sample Video", parsed.initialPlayerResponse?.stringAt("videoDetails", "title"))
    }

    @Test
    fun resolvesProtectedFormatsAndNParameter() {
        val playerJs = resourceText("fixtures/youtube/player/sample-player.js")
        val protected = NativeAudioFormat(
            url = null,
            formatId = "140",
            ext = "m4a",
            mimeType = "audio/mp4",
            audioCodec = "mp4a.40.2",
            averageBitrate = 128.0,
            source = "ios-player-api",
            signatureCipher = "url=https%3A%2F%2Fexample.com%2Faudio%3Ffoo%3Dbar%26n%3Dabcd&s=abcdef&sp=signature",
        )

        val resolved = PlayerJsDecipherer.resolveProtectedFormats(listOf(protected), playerJs).single()
        val resolvedUrl = resolved.url

        assertNotNull(resolvedUrl)
        assertTrue(resolvedUrl.contains("signature="))
        assertTrue(resolvedUrl.contains("n=dcba"))
    }

    @Test
    fun resolvesMediaFixture() {
        val html = resourceText("fixtures/youtube/watch/sample-watch.html")
        val parsed = YoutubeWatchPageParser.parseWatchData("https://www.youtube.com/watch?v=dQw4w9WgXcQ", html)
        val resolved = YoutubeMediaResolver.resolveMedia(
            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            initialPlayerResponse = parsed.initialPlayerResponse,
            playerResponses = emptyList(),
        )

        assertEquals("Sample Video", resolved.metadata.title)
        assertEquals(1, resolved.audioFormats.size)
        assertEquals("mp4", resolved.audioFormats.single().ext)
    }

    private fun resourceText(path: String): String {
        return requireNotNull(this::class.java.classLoader.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }.bufferedReader().readText()
    }
}

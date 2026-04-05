package dev.lizz.ytdl.providers.youtube.probe

import dev.lizz.ytdl.core.MediaMetadata
import dev.lizz.ytdl.providers.youtube.NativeAudioFormat
import dev.lizz.ytdl.providers.youtube.NativeManifest
import dev.lizz.ytdl.providers.youtube.ResolvedYoutubeMedia
import kotlin.test.Test
import kotlin.test.assertEquals

class YoutubeAudioPlannerTest {
    @Test
    fun `orders direct formats and manifests deterministically`() {
        val low = NativeAudioFormat(
            url = "https://example.com/low.m4a",
            formatId = "139",
            ext = "m4a",
            mimeType = "audio/mp4",
            audioCodec = "mp4a.40.2",
            videoCodec = null,
            averageBitrate = 48.0,
            source = "ios-player-api",
        )
        val high = low.copy(url = "https://example.com/high.m4a", formatId = "140", averageBitrate = 128.0)
        val media = ResolvedYoutubeMedia(
            metadata = MediaMetadata(title = "Sample", sourceUrl = "https://example.com"),
            audioFormats = listOf(low, high),
            hlsManifestUrls = listOf(NativeManifest(url = "https://example.com/master.m3u8", source = "watch-page", kind = "hls")),
            dashManifestUrls = listOf(NativeManifest(url = "https://example.com/audio.mpd", source = "watch-page", kind = "dash")),
        )

        val plan = YoutubeAudioPlanner.plan(media, manifestPriority = listOf("dash", "hls")) { formats ->
            formats.maxBy { it.averageBitrate ?: 0.0 }
        }

        assertEquals("140", plan.preferredDirectFormat?.formatId)
        assertEquals(listOf("140", "139"), plan.directFormats.mapNotNull { it.formatId })
        assertEquals(listOf("dash", "hls"), plan.manifestFallbacks.map { it.kind })
    }
}

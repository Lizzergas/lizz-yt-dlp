package dev.lizz.ytdl.engine.youtube

import dev.lizz.ytdl.core.VideoMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object YoutubeMediaResolver {
    fun resolveMedia(
        originalUrl: String,
        initialPlayerResponse: JsonObject?,
        playerResponses: List<Pair<String, JsonObject>>,
    ): ResolvedYoutubeMedia {
        val formats = buildList {
            playerResponses.forEach { (source, player) -> addAll(extractAudioFormats(player, source)) }
            addAll(extractAudioFormats(initialPlayerResponse, "watch-page"))
        }.distinctBy { it.url ?: it.signatureCipher ?: it.formatId ?: it.source }

        val manifests = buildList {
            playerResponses.forEach { (source, player) -> addAll(extractManifests(player, source)) }
            addAll(extractManifests(initialPlayerResponse, "watch-page"))
        }.distinctBy { it.url }

        if (formats.none { it.url != null } && manifests.isEmpty()) {
            throw IllegalStateException(
                "Native engine could not find any direct audio URLs or manifests. This video likely requires signature or n-parameter solving that is not implemented yet."
            )
        }

        val title = listOfNotNull(
            playerResponses.firstNotNullOfOrNull { (_, player) -> player.stringAt("videoDetails", "title") },
            initialPlayerResponse?.stringAt("videoDetails", "title"),
        ).firstOrNull().orEmpty().ifBlank { YoutubeWatchPageParser.parseYoutubeId(originalUrl) ?: "youtube-audio" }

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
            compareBy<NativeAudioFormat>({ if (it.ext == "m4a") 1 else 0 }, { it.averageBitrate ?: -1.0 })
        ) ?: throw IllegalStateException("No usable audio format found")
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
            val acodec = codecs.firstOrNull { codec -> codec.startsWith("mp4a") || codec.startsWith("opus") || codec.startsWith("vorbis") }
                ?: if (mimeType?.startsWith("audio/") == true) mimeType.substringAfter('/') else null
            if (acodec == null) return@mapNotNull null

            val ext = mimeType?.substringBefore(';')?.substringAfter('/')?.ifBlank { null }
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

    private fun extractManifests(player: JsonObject?, source: String): List<NativeManifest> {
        val streamingData = player?.objectAt("streamingData") ?: return emptyList()
        return buildList {
            streamingData.string("hlsManifestUrl")?.let { add(NativeManifest(url = it, source = source, kind = "hls")) }
            streamingData.string("dashManifestUrl")?.let { add(NativeManifest(url = it, source = source, kind = "dash")) }
        }
    }
}

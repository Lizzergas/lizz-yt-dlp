package dev.lizz.ytdl.engine.youtube

import dev.lizz.ytdl.core.VideoMetadata
import kotlinx.serialization.json.JsonObject

internal data class NativeAudioFormat(
    val url: String?,
    val formatId: String?,
    val ext: String,
    val mimeType: String?,
    val audioCodec: String?,
    val videoCodec: String?,
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
    val dashManifestUrls: List<NativeManifest>,
)

internal data class NativeManifest(
    val url: String,
    val source: String,
    val kind: String,
)

internal data class PlayerClientConfig(
    val label: String,
    val context: JsonObject,
    val headerClientName: String,
    val headerClientVersion: String,
    val userAgent: String,
    val visitorData: String?,
)

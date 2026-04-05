package dev.lizz.ytdl.providers.youtube.net

internal data class YoutubeHttpResult(
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String? = null,
)

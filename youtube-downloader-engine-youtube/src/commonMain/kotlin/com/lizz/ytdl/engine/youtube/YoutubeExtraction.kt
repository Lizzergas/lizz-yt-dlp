package com.lizz.ytdl.engine.youtube

internal class YoutubeExtractorCommons {
    fun parseYoutubeId(url: String): String? = YoutubeWatchPageParser.parseYoutubeId(url)

    fun parseWatchData(url: String, watchPageHtml: String): ExtractedWatchData =
        YoutubeWatchPageParser.parseWatchData(url, watchPageHtml)

    fun resolveMedia(
        originalUrl: String,
        initialPlayerResponse: kotlinx.serialization.json.JsonObject?,
        playerResponses: List<Pair<String, kotlinx.serialization.json.JsonObject>>,
    ): ResolvedYoutubeMedia = YoutubeMediaResolver.resolveMedia(originalUrl, initialPlayerResponse, playerResponses)

    fun pickBestAudioFormat(formats: List<NativeAudioFormat>): NativeAudioFormat =
        YoutubeMediaResolver.pickBestAudioFormat(formats)

    fun createInnertubeClients(ytcfg: kotlinx.serialization.json.JsonObject): List<PlayerClientConfig> =
        YoutubeClientProfiles.createInnertubeClients(ytcfg)

    companion object {
        const val BROWSER_USER_AGENT: String = YoutubeConstants.BROWSER_USER_AGENT
    }
}

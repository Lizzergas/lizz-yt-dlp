package dev.lizz.ytdl.providers.youtube

import dev.lizz.ytdl.core.ProviderId

internal object YoutubeProviderSupport {
    val providerId: ProviderId = ProviderId.YOUTUBE

    private val youtubeUrl = Regex(
        pattern = """^(?:https?://)?(?:(?:www|m|music)\.)?(?:youtube\.com|youtu\.be)/.+$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun canHandle(locator: String): Boolean = youtubeUrl.matches(locator.trim())
}

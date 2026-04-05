package dev.lizz.ytdl.providers.youtube

import dev.lizz.ytdl.core.DefaultMediaClient
import dev.lizz.ytdl.core.MediaClient
import dev.lizz.ytdl.providers.youtube.IosYoutubeProvider

/** Creates an iOS media client with the YouTube provider registered. */
public object IosYoutubeProviderFactory {
    /** Builds the default iOS media client. */
    public fun createDefault(): MediaClient {
        return DefaultMediaClient(listOf(IosYoutubeProvider()))
    }
}

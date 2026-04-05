package dev.lizz.ytdl.providers.youtube

import dev.lizz.ytdl.core.DefaultMediaClient
import dev.lizz.ytdl.core.MediaClient
import dev.lizz.ytdl.providers.youtube.JvmYoutubeProvider

/** Creates a JVM media client with the YouTube provider registered. */
public object JvmYoutubeProviderFactory {
    /** Builds the default JVM/Desktop media client. */
    public fun createDefault(): MediaClient {
        return DefaultMediaClient(listOf(JvmYoutubeProvider()))
    }
}

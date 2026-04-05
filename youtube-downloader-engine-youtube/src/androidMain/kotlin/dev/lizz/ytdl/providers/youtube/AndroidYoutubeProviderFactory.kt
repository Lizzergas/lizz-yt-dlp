package dev.lizz.ytdl.providers.youtube

import android.content.Context
import dev.lizz.ytdl.core.DefaultMediaClient
import dev.lizz.ytdl.core.MediaClient

/** Creates an Android media client with the YouTube provider registered. */
public object AndroidYoutubeProviderFactory {
    /** Builds the default Android media client using the application context. */
    public fun create(context: Context): MediaClient {
        return DefaultMediaClient(listOf(AndroidYoutubeProvider(context.applicationContext)))
    }
}

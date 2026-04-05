package dev.lizz.ytdl.providers.youtube.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class YoutubeResponseCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Any>()

    suspend fun <T : Any> getOrLoad(
        key: String,
        loader: suspend () -> T,
    ): T {
        mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            val cached = entries[key] as? T
            if (cached != null) return cached
        }

        val loaded = loader()
        return mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            val cached = entries[key] as? T
            if (cached != null) cached else loaded.also { entries[key] = it }
        }
    }
}

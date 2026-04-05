package dev.lizz.ytdl.providers.youtube.net

import dev.lizz.ytdl.providers.youtube.errors.YoutubeFailure
import kotlinx.coroutines.delay

internal object YoutubeRetryPolicy {
    suspend fun <T> run(
        maxAttempts: Int = 2,
        initialDelayMs: Long = 150,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                return block()
            } catch (error: Throwable) {
                lastError = error
                if (attempt >= maxAttempts || !shouldRetry(error)) throw error
                delay(initialDelayMs * attempt)
            }
        }
        throw lastError ?: IllegalStateException("Retry policy exhausted without an error")
    }

    private fun shouldRetry(error: Throwable): Boolean {
        if (error is YoutubeFailure) return false
        val message = error.message?.lowercase().orEmpty()
        if (message.startsWith("http 4") && !message.startsWith("http 408") && !message.startsWith("http 429")) return false
        return message.contains("timeout") ||
            message.contains("tempor") ||
            message.contains("connection") ||
            message.contains("reset") ||
            message.contains("broken pipe") ||
            message.contains("eof") ||
            message.contains("http 5") ||
            message.startsWith("http 408") ||
            message.startsWith("http 429")
    }
}

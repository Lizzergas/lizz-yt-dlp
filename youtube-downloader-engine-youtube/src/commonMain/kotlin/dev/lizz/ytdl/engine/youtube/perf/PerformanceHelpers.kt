package dev.lizz.ytdl.engine.youtube.perf

import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal const val DOWNLOAD_BUFFER_SIZE_BYTES: Int = 64 * 1024
internal const val UNKNOWN_TOTAL_PROGRESS_STEP_BYTES: Long = 256 * 1024
internal const val HLS_DOWNLOAD_PARALLELISM: Int = 6

internal class ProgressStepTracker(
    private val percentStep: Int = 1,
    private val bytesStep: Long = UNKNOWN_TOTAL_PROGRESS_STEP_BYTES,
) {
    private var lastPercent = -1
    private var lastBytes = 0L

    fun percent(totalBytes: Long?, downloadedBytes: Long): Int? {
        return totalBytes
            ?.takeIf { it > 0L }
            ?.let { ((downloadedBytes.toDouble() / it) * 100).roundToInt().coerceIn(0, 100) }
    }

    fun shouldEmit(totalBytes: Long?, downloadedBytes: Long, force: Boolean = false): Boolean {
        if (force) {
            lastPercent = percent(totalBytes, downloadedBytes) ?: lastPercent
            lastBytes = downloadedBytes
            return true
        }

        val percent = percent(totalBytes, downloadedBytes)
        if (percent != null) {
            if (percent >= lastPercent + percentStep) {
                lastPercent = percent
                lastBytes = downloadedBytes
                return true
            }
            return false
        }

        if (downloadedBytes - lastBytes >= bytesStep) {
            lastBytes = downloadedBytes
            return true
        }
        return false
    }
}

internal suspend fun <T, R> parallelMapBatched(
    items: List<T>,
    parallelism: Int,
    transform: suspend (T) -> R,
): List<R> {
    if (items.isEmpty()) return emptyList()
    if (parallelism <= 1) return items.map { transform(it) }

    return buildList(items.size) {
        for (batch in items.chunked(parallelism)) {
            addAll(coroutineScope { batch.map { async { transform(it) } }.awaitAll() })
        }
    }
}

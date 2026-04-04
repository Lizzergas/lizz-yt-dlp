package com.lizz.ytdl.engine.youtube

internal fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
}

internal fun NativeAudioFormat.describe(): String {
    return listOfNotNull(ext, averageBitrate?.toInt()?.let { "$it kbps" }, audioCodec, source).joinToString(" | ")
}

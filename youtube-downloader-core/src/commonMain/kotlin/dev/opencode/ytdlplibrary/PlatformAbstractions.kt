package com.lizz.ytdl.core

interface OutputPathResolver {
    suspend fun resolveAudioPath(
        suggestedFileName: String,
        extension: String = "mp3",
    ): String
}

interface AudioTranscoder {
    suspend fun transcodeToMp3(
        inputPath: String,
        outputPath: String,
        durationSeconds: Int? = null,
        emit: suspend (DownloadEvent) -> Unit = {},
    )
}

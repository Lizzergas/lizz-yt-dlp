package dev.lizz.ytdl.core

/** Resolves the final MP3 output location on the current platform. */
public interface OutputPathResolver {
    public suspend fun resolveAudioPath(
        suggestedFileName: String,
        extension: String = "mp3",
    ): String
}

/** Converts a downloaded media file into MP3 on the current platform. */
public interface AudioTranscoder {
    public suspend fun transcodeToMp3(
        inputPath: String,
        outputPath: String,
        durationSeconds: Int? = null,
        emit: suspend (DownloadEvent) -> Unit = {},
    )
}

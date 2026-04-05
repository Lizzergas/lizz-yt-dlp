package dev.lizz.ytdl.providers.youtube.errors

internal sealed class YoutubeFailure(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class NoAudioCandidatesFailure : YoutubeFailure(
    "Native engine could not find any direct audio URLs or manifests. This video likely requires signature or n-parameter solving that is not implemented yet.",
)

internal class ProtectedFormatUnresolvedFailure : YoutubeFailure(
    "Protected audio formats were detected, but the native player JS solver could not resolve a usable URL.",
)

internal class TranscriptUnavailableFailure(languageCode: String) : YoutubeFailure(
    "No transcript track was available for '$languageCode'.",
)

internal class LanguageUnavailableFailure(languageCode: String) : YoutubeFailure(
    "Requested transcript language '$languageCode' was not available.",
)

internal class AuthRequiredFailure : YoutubeFailure(
    "This YouTube resource requires authentication.",
)

internal class TransientNetworkFailure(
    message: String,
    cause: Throwable? = null,
) : YoutubeFailure(message, cause)

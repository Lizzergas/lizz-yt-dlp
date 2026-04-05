# Usage

`lizz-yt-dlp` is a Kotlin Multiplatform library for downloading YouTube audio as MP3 and retrieving English transcripts.

## Add Dependencies

```kotlin
repositories {
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.lizz.ytdl:youtube-downloader-core:0.1.0-alpha04")
            implementation("dev.lizz.ytdl:youtube-downloader-engine-youtube:0.1.0-alpha04")
        }
    }
}
```

Keep the dependencies in `commonMain`, but create the media client with the provider factory from `jvmMain`, `androidMain`, or `iosMain`.

## Public API

The shared API lives in `dev.lizz.ytdl.core`.

Main types:

- `ProviderId`
- `AudioDownloadOptions`
- `AudioDownloadRequest`
- `AudioDownloadResult`
- `TranscriptRequest`
- `MediaEvent`
- `MediaClient`

## Create A Client

Use the factory for the current platform.

JVM or Desktop:

```kotlin
import dev.lizz.ytdl.providers.youtube.JvmYoutubeProviderFactory

val client = JvmYoutubeProviderFactory.createDefault()
```

Android:

```kotlin
import android.content.Context
import dev.lizz.ytdl.providers.youtube.AndroidYoutubeProviderFactory

fun createClient(context: Context) =
    AndroidYoutubeProviderFactory.create(context)
```

iOS:

```kotlin
import dev.lizz.ytdl.providers.youtube.IosYoutubeProviderFactory

val client = IosYoutubeProviderFactory.createDefault()
```

## Basic Download

```kotlin
import dev.lizz.ytdl.core.AudioDownloadOptions
import dev.lizz.ytdl.core.AudioDownloadRequest

suspend fun downloadMp3(): String {
    val result = client.downloadAudio(
        AudioDownloadRequest(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            options = AudioDownloadOptions(outputPath = "./downloads"),
        )
    )

    return result.path
}
```

If you want explicit provider routing for a future multi-provider client, set `provider = ProviderId.YOUTUBE` on the request.

## Progress And Events

Use the callback overload to receive structured progress updates.

```kotlin
import dev.lizz.ytdl.core.MediaEvent

val result = client.downloadAudio(request) { event ->
    when (event) {
        is MediaEvent.StageChanged -> println("${event.stage.label}: ${event.message}")
        is MediaEvent.MetadataResolved -> println("Title: ${event.metadata.title}")
        is MediaEvent.ProgressChanged -> println(event.snapshot.label)
        is MediaEvent.OutputResolved -> println("Output: ${event.path}")
        is MediaEvent.Completed -> println("Done: ${event.outputPath}")
        is MediaEvent.Failed -> println("Failed: ${event.message}")
        is MediaEvent.LogEmitted -> println(event.message)
        is MediaEvent.WorkingFileResolved -> Unit
    }
}
```

## Transcript API

If English subtitles or automatic captions exist, you can retrieve them as plain text or as structured cues.

Plain text:

```kotlin
val transcript = client.getTranscript(
    TranscriptRequest(
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        includeTimecodes = false,
    )
)
```

Plain text with inline timecodes:

```kotlin
val transcriptWithTimecodes = client.getTranscript(
    TranscriptRequest(
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        includeTimecodes = true,
    )
)
```

Structured cues:

```kotlin
val transcriptResult = client.getTranscriptCues(
    TranscriptRequest(url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
)

transcriptResult?.cues?.forEach { cue ->
    println("${cue.startMs} -> ${cue.endMs}: ${cue.text}")
}
```

Behavior:

- English only
- manual subtitles are preferred over automatic captions
- returns `null` when no English subtitles or captions are available
- plain transcript mode removes WebVTT timing and de-duplicates adjacent identical cues

## Output Path Rules

`AudioDownloadOptions.outputPath` accepts either a directory or a file path.

Examples:

- `"./downloads"` -> save into that directory using the resolved video title
- `"./downloads/track.mp3"` -> save to that exact file name
- `null` -> use the platform default

Platform defaults:

- JVM/Desktop: current working directory
- Android: app internal `filesDir`
- iOS: app `Documents` directory

If a file already exists, the library appends ` (1)`, ` (2)`, and so on.

## Compose Multiplatform Pattern

The clean pattern is:

1. depend on the library in `commonMain`
2. create the client in platform code
3. pass a small shared wrapper into your UI or shared business logic

Example shared contract:

```kotlin
interface AppDownloader {
    suspend fun download(
        request: AudioDownloadRequest,
        emit: suspend (MediaEvent) -> Unit = {},
    ): AudioDownloadResult
}
```

Then implement it per platform by delegating to the library factory.

## Current Limits

- YouTube only
- audio download and English transcript extraction only
- transcript APIs currently prefer English only; there is no explicit language selector yet
- transcript APIs do not expose translated subtitle retrieval yet
- some protected formats still fall back to HLS or fail
- JVM/Desktop currently requires local `ffmpeg` for MP3 conversion

## Platform Notes

Android:

- pass an application or activity `Context` into `AndroidYoutubeProviderFactory.create(...)`
- files are written into app-internal storage unless you set `outputPath`

iOS:

- `IosYoutubeProviderFactory.createDefault()` requires no extra setup
- default output goes to the app `Documents` directory

JVM/Desktop:

- `JvmYoutubeProviderFactory.createDefault()` is enough to start
- `ffmpeg` must be available on `PATH` for MP3 conversion

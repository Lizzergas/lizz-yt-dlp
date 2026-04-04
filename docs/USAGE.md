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
            implementation("dev.lizz.ytdl:youtube-downloader-core:0.1.0-alpha03")
            implementation("dev.lizz.ytdl:youtube-downloader-engine-youtube:0.1.0-alpha03")
        }
    }
}
```

Keep the dependencies in `commonMain`, but create the downloader with the platform factory from `jvmMain`, `androidMain`, or `iosMain`.

## Public API

The shared API lives in `dev.lizz.ytdl.core`.

Main types:

- `DownloadOptions`
- `DownloadRequest`
- `DownloadResult`
- `DownloadEvent`
- `YoutubeDownloader`

## Create A Downloader

Use the factory for the current platform.

JVM or Desktop:

```kotlin
import dev.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory

val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()
```

Android:

```kotlin
import android.content.Context
import dev.lizz.ytdl.engine.youtube.AndroidNativeYoutubeDownloaderFactory

fun createDownloader(context: Context) =
    AndroidNativeYoutubeDownloaderFactory.create(context)
```

iOS:

```kotlin
import dev.lizz.ytdl.engine.youtube.IosNativeYoutubeDownloaderFactory

val downloader = IosNativeYoutubeDownloaderFactory.createDefault()
```

## Basic Download

```kotlin
import dev.lizz.ytdl.core.DownloadOptions
import dev.lizz.ytdl.core.DownloadRequest

suspend fun downloadMp3(): String {
    val result = downloader.download(
        DownloadRequest(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            options = DownloadOptions(outputPath = "./downloads"),
        )
    )

    return result.path
}
```

You can also use the short form:

```kotlin
val path = downloader.download("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
```

## Progress And Events

Use the callback overload to receive structured progress updates.

```kotlin
import dev.lizz.ytdl.core.DownloadEvent

val result = downloader.download(request) { event ->
    when (event) {
        is DownloadEvent.StageChanged -> println("${event.stage.label}: ${event.message}")
        is DownloadEvent.MetadataResolved -> println("Title: ${event.metadata.title}")
        is DownloadEvent.ProgressChanged -> println(event.snapshot.label)
        is DownloadEvent.OutputResolved -> println("Output: ${event.path}")
        is DownloadEvent.Completed -> println("Done: ${event.outputPath}")
        is DownloadEvent.Failed -> println("Failed: ${event.message}")
        is DownloadEvent.LogEmitted -> println(event.message)
        is DownloadEvent.WorkingFileResolved -> Unit
    }
}
```

## Transcript API

If English subtitles or automatic captions exist, you can retrieve them as plain text or as structured cues.

Plain text:

```kotlin
val transcript = downloader.getTranscript(
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    includeTimecodes = false,
)
```

Plain text with inline timecodes:

```kotlin
val transcriptWithTimecodes = downloader.getTranscript(
    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    includeTimecodes = true,
)
```

Structured cues:

```kotlin
val transcriptResult = downloader.getTranscriptCues("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

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

`DownloadOptions.outputPath` accepts either a directory or a file path.

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
2. create the downloader in platform code
3. pass a small shared wrapper into your UI or shared business logic

Example shared contract:

```kotlin
interface AppDownloader {
    suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
}
```

Then implement it per platform by delegating to the library factory.

## Current Limits

- YouTube only
- audio only
- some protected formats still fall back to HLS or fail
- JVM/Desktop currently requires local `ffmpeg` for MP3 conversion

## Platform Notes

Android:

- pass an application or activity `Context` into `AndroidNativeYoutubeDownloaderFactory.create(...)`
- files are written into app-internal storage unless you set `outputPath`

iOS:

- `IosNativeYoutubeDownloaderFactory.createDefault()` requires no extra setup
- default output goes to the app `Documents` directory

JVM/Desktop:

- `JvmNativeYoutubeDownloaderFactory.createDefault()` is enough to start
- `ffmpeg` must be available on `PATH` for MP3 conversion

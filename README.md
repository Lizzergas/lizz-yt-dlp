# lizz-yt-dlp

Kotlin-first YouTube audio downloader library for JVM, Android, iOS, and Compose Multiplatform.

## Artifacts

```kotlin
repositories {
            mavenCentral()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.lizz.ytdl:youtube-downloader-core:0.1.0-alpha02")
            implementation("dev.lizz.ytdl:youtube-downloader-engine-youtube:0.1.0-alpha02")
        }
    }
}
```

Published Maven coordinates and Kotlin packages use `dev.lizz.ytdl`.

## Usage

```kotlin
import dev.lizz.ytdl.core.DownloadOptions
import dev.lizz.ytdl.core.DownloadRequest
import dev.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory

suspend fun downloadAudio(): String {
    val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()
    return downloader.download(
        DownloadRequest(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            options = DownloadOptions(outputPath = "./downloads")
        )
    ).path
}
```

Android uses `AndroidNativeYoutubeDownloaderFactory.create(context)`.

iOS uses `IosNativeYoutubeDownloaderFactory.createDefault()`.

## Modules

- `youtube-downloader-core`: public KMP API
- `youtube-downloader-engine-youtube`: YouTube extraction and download engine
- `android-native-media`: owned Android LAME bridge used by the engine
- `samples`: Compose Multiplatform sample module
- `androidApp`: Android sample host app
- `iosApp`: iOS sample host app

## Current Limits

- YouTube-only
- audio download only
- `signatureCipher`, `n`, and anti-bot handling are still incomplete
- JVM/Desktop MP3 conversion still requires local `ffmpeg` for now

## Development

```bash
./gradlew check
./gradlew publishAllPublicationsToBuildRepo
./gradlew :samples:compileKotlinDesktop
./gradlew :androidApp:assembleDebug
./gradlew :iosApp:compileKotlinIosSimulatorArm64
```

Release and architecture notes live in `docs/`.

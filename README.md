# lizz-yt-dlp

Provider-based Kotlin Multiplatform media client with a built-in YouTube provider for audio downloads and English transcripts.

## Artifacts

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

Published Maven coordinates and Kotlin packages use `dev.lizz.ytdl`.

## Usage

```kotlin
import dev.lizz.ytdl.core.AudioDownloadOptions
import dev.lizz.ytdl.core.AudioDownloadRequest
import dev.lizz.ytdl.providers.youtube.JvmYoutubeProviderFactory

suspend fun downloadAudio(): String {
    val client = JvmYoutubeProviderFactory.createDefault()
    return client.downloadAudio(
        AudioDownloadRequest(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            options = AudioDownloadOptions(outputPath = "./downloads")
        )
    ).path
}
```

Android uses `AndroidYoutubeProviderFactory.create(context)`.

iOS uses `IosYoutubeProviderFactory.createDefault()`.

## Modules

- `youtube-downloader-core`: public KMP API
- `youtube-downloader-engine-youtube`: built-in YouTube provider implementation and factories
- `android-native-media`: owned Android LAME bridge used by the engine
- `samples`: Compose Multiplatform sample module
- `androidApp`: Android sample host app
- `iosApp`: iOS sample host app

## Current Limits

- YouTube-only
- audio download and English transcript extraction only
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

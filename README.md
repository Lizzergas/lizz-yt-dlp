# kt-ytdl Demo

This repository is now **Kotlin-native only** from the downloader/runtime perspective.

There is no longer any Gradle module in this project that wraps or invokes `yt-dlp` as a backend engine.

## Modules

- `youtube-downloader-core`: Kotlin Multiplatform public API
- `youtube-downloader-engine-youtube`: pure Kotlin YouTube extraction and download engine
- `android-native-media`: owned Android JNI/CMake MP3 encoder bridge using vendored LAME source
- `iosApp`: Xcode iOS host app for the shared Compose framework
- `sample-cli`: Clikt sample app for scripting and smoke testing
- `sample-terminal`: Mosaic terminal sample app
- `sample-compose`: shared Compose Multiplatform sample UI module
- `androidApp`: Android app entrypoint for the Compose sample

## Public API

```kotlin
interface YoutubeDownloader {
    suspend fun download(url: String): String

    suspend fun download(
        request: DownloadRequest,
        emit: suspend (DownloadEvent) -> Unit = {},
    ): DownloadResult
}
```

Current JVM usage:

```kotlin
import com.lizz.ytdl.core.DownloadOptions
import com.lizz.ytdl.core.DownloadRequest
import com.lizz.ytdl.engine.youtube.JvmNativeYoutubeDownloaderFactory

suspend fun example(): String {
    val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()

    return downloader.download(
        DownloadRequest(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            options = DownloadOptions(outputPath = "./downloads/")
        )
    ).path
}
```

Simple form:

```kotlin
val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()
val path = downloader.download("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
```

## Build

```bash
./gradlew :sample-cli:installDist
./gradlew :sample-terminal:installDist
./gradlew :sample-compose:compileKotlinDesktop
./gradlew :androidApp:assembleDebug
```

## Run The CLI Sample

```bash
./sample-cli/build/install/sample-cli/bin/sample-cli "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
./sample-cli/build/install/sample-cli/bin/sample-cli -o ./downloads/ "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
./sample-cli/build/install/sample-cli/bin/sample-cli --verbose "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
./sample-cli/build/install/sample-cli/bin/sample-cli --json-progress "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

## Run The Terminal Sample

```bash
./sample-terminal/build/install/sample-terminal/bin/sample-terminal "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

## Compose Multiplatform Sample

Current state:

- Desktop/JVM: real native downloader wiring through `JvmNativeYoutubeDownloaderFactory`
- Android: real downloader and owned MP3 path are wired into the app
- iOS: real app host exists, shared framework builds, and the iOS engine uses Ktor Darwin plus an owned Apple media bridge

Useful commands:

```bash
./gradlew :sample-compose:compileKotlinDesktop
./gradlew :sample-compose:compileKotlinIosSimulatorArm64
./gradlew :androidApp:assembleDebug
./gradlew :iosApp:compileKotlinIosSimulatorArm64
```

## Optional Flags

```bash
--strict-certs
--help
```

## Current Native Engine Status

What works now:

- pure Kotlin native YouTube engine module
- JVM on-device download path
- Android on-device downloader path compiled, wired, and manually validated on direct and HLS-fallback cases
- Android owned native media module compiled for `arm64-v8a` and `x86_64`
- Android HLS fallback now downloads audio fragments locally before MP3 transcoding
- iOS app host builds from Xcode / Android Studio-compatible structure
- iOS engine and owned Apple media bridge compile
- mp3 output through the native engine path used by the samples
- direct media download when URLs are usable
- HLS fallback when direct URLs are rejected
- event/progress reporting through the shared API

What is still incomplete:

- broader `signatureCipher` coverage
- `n` parameter solving parity
- more complete anti-bot / attestation handling
- Android runtime verification on a physical device
- iOS runtime verification and hardening on simulator/device
- more shared `kotlinx-io` adoption across runtime stream/file paths

## Multiplatform Direction

The KMP core already exposes the right abstractions for a fully on-device multiplatform library:

- `YoutubeDownloadEngine`
- `OutputPathResolver`
- `AudioTranscoder`

The remaining work is to complete the native YouTube extraction/parsing path and then add platform-native download/transcoding implementations for Android and iOS.

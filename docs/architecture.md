# Architecture

## Goal

`lizz-yt-dlp` is a Kotlin Multiplatform library that downloads YouTube audio on-device.

## Modules

- `youtube-downloader-core`: public request/result/progress API
- `youtube-downloader-engine-youtube`: YouTube extraction, media selection, download, and platform factories
- `android-native-media`: Android JNI/CMake bridge over vendored LAME
- `samples`: Compose Multiplatform sample module
- `androidApp`: Android sample host app
- `iosApp`: iOS sample host app

## Runtime Flow

1. App code creates a platform downloader factory.
2. The engine fetches and parses the watch page.
3. The engine requests multiple Innertube player responses.
4. Audio formats are normalized and ranked.
5. Protected formats are resolved with the shared player-JS subsystem when possible.
6. Direct download is attempted first, then HLS fallback is used when needed.
7. The downloaded media is transcoded to MP3 by the platform runtime.

## Platform Notes

- JVM/Desktop uses `java.net.http.HttpClient` and still shells out to `ffmpeg` for MP3 conversion.
- Android uses `HttpURLConnection`, local HLS fragment assembly, and the owned LAME bridge.
- iOS uses `Ktor Darwin`, local file writes, and the owned Apple-side LAME bridge.

## Current Limits

- YouTube-only
- audio-only
- partial `signatureCipher` coverage
- incomplete `n` and anti-bot handling
- JVM MP3 conversion has not yet been migrated to the owned LAME path

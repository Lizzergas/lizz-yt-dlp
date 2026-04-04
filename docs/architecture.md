# Architecture

## Goal

This project is building a Kotlin-first, on-device YouTube audio downloader that can be embedded into Compose Multiplatform applications.

The current implementation is not full `yt-dlp` parity. It is a native Kotlin prototype with:

- a shared downloader API in KMP
- a native YouTube extraction engine
- JVM runtime support
- Android runtime support
- iOS runtime support in code
- Compose sample apps for Desktop and Android
- an Xcode iOS host app for the shared Compose framework

The project no longer contains an active `yt-dlp` backend adapter module.

## Module Map

### `youtube-downloader-core`

Purpose:

- stable public API
- shared request/result/progress models
- engine abstraction

Key file:

- `youtube-downloader-core/src/commonMain/kotlin/dev/opencode/ytdlplibrary/YoutubeDownloader.kt`

Important note:

- the source file still lives in a legacy folder path, but the package namespace is `com.lizz.ytdl.core`

Main types:

- `DownloadRequest`
- `DownloadOptions`
- `DownloadResult`
- `DownloadEvent`
- `YoutubeDownloadEngine`
- `YoutubeDownloader`
- `DefaultYoutubeDownloader`

The actual public entrypoint consumers should use is a concrete factory from a platform engine module, for example:

```kotlin
val downloader = JvmNativeYoutubeDownloaderFactory.createDefault()
val path = downloader.download("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
```

### `youtube-downloader-engine-youtube`

Purpose:

- native YouTube extraction and download implementation
- shared extraction logic in `commonMain`
- platform-specific transport/downloader/transcoder code in `jvmMain` and `androidMain`

Main files:

- `src/commonMain/kotlin/com/lizz/ytdl/engine/youtube/YoutubeExtraction.kt`
- `src/commonMain/kotlin/com/lizz/ytdl/engine/youtube/watch/`
- `src/commonMain/kotlin/com/lizz/ytdl/engine/youtube/playerjs/`
- `src/jvmMain/kotlin/com/lizz/ytdl/engine/youtube/JvmNativeYoutubeDownloadEngine.kt`
- `src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidNativeYoutubeDownloadEngine.kt`
- `src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidMp3Transcoder.kt`
- `src/iosMain/kotlin/com/lizz/ytdl/engine/youtube/IosNativeYoutubeDownloadEngine.kt`
- `src/iosMain/kotlin/com/lizz/ytdl/engine/youtube/IosMp3Transcoder.kt`

This module owns the runtime logic for:

- YouTube URL parsing
- watch-page parsing
- `ytcfg` extraction
- Innertube player calls
- direct audio format normalization
- shared `signatureCipher` solving
- shared `n` rewriting
- HLS fallback
- MP3 conversion through platform-specific implementations

### `android-native-media`

Purpose:

- owned Android-native MP3 encoding bridge
- JNI wrapper around vendored LAME source
- no dependency on archived FFmpegKit

Main files:

- `android-native-media/src/main/java/com/lizz/ytdl/androidmedia/LameEncoderBridge.kt`
- `android-native-media/src/main/cpp/ytdl_android_media.cpp`
- `android-native-media/src/main/cpp/CMakeLists.txt`
- `android-native-media/build.gradle.kts`

### `sample-cli`

Purpose:

- fastest smoke-test surface for the native engine
- easy to run outside the Compose UI

### `sample-terminal`

Purpose:

- Mosaic terminal demo
- event/progress visualization over the same downloader API

### `sample-compose`

Purpose:

- shared Compose Multiplatform UI layer
- Desktop real downloader wiring
- Android real downloader wiring
- iOS real downloader wiring

Main files:

- `sample-compose/src/commonMain/kotlin/com/lizz/ytdl/sample/compose/SampleApp.kt`
- `sample-compose/src/commonMain/kotlin/com/lizz/ytdl/sample/compose/SampleDownloader.kt`
- `sample-compose/src/desktopMain/kotlin/com/lizz/ytdl/sample/compose/DesktopSampleDownloader.kt`
- `sample-compose/src/androidMain/kotlin/com/lizz/ytdl/sample/compose/AndroidSampleDownloader.kt`

### `androidApp`

Purpose:

- Android app entrypoint required for AGP 9 compatibility
- hosts `MainActivity`
- passes an Android-aware downloader into the shared Compose UI

### `iosApp`

Purpose:

- Xcode iOS host app for the shared Compose framework
- standard Compose Multiplatform iOS entrypoint layout
- integrates `SampleCompose` into an actual Apple app host

## Runtime Flow

### 1. Consumer API

The public call path is:

```kotlin
YoutubeDownloader.download(url: String): String
```

or the richer variant:

```kotlin
YoutubeDownloader.download(request, emit): DownloadResult
```

The `emit` callback streams structured `DownloadEvent` updates for UI or CLI consumers.

### 2. Shared Extraction Logic

Shared extraction lives in `YoutubeExtractorCommons` in `YoutubeExtraction.kt`.

Responsibilities:

- extract video id from the incoming URL
- fetch and parse the watch page
- extract `ytcfg`
- extract `ytInitialPlayerResponse`
- determine the player JS URL
- define Innertube client profiles
- normalize direct audio formats
- collect HLS/DASH manifests

Main data types:

- `ExtractedWatchData`
- `ResolvedYoutubeMedia`
- `NativeAudioFormat`
- `NativeManifest`

### 3. Watch Page Parsing

The native engine downloads the watch page and then:

1. identifies the 11-character video id
2. extracts `ytcfg` using string marker search plus balanced JSON parsing
3. extracts `ytInitialPlayerResponse` the same way
4. extracts `playerUrl` from either:
   - `PLAYER_JS_URL`
   - `WEB_PLAYER_CONTEXT_CONFIGS...jsUrl`
   - direct HTML `jsUrl`/`base.js` references

This parsing is intentionally lightweight. It does not use a full HTML parser.

### 4. Innertube Client Strategy

The engine tries multiple client personas, currently:

- `web-player-api`
- `ios-player-api`
- `tv-player-api`
- `android-player-api`

This mirrors one of the key `yt-dlp` ideas: do not trust a single player response.

Each client may expose different:

- direct media URLs
- ciphered URLs
- manifest URLs
- or no usable `streamingData` at all

In practice, the iOS client is frequently the most useful for direct audio URLs in this prototype.

### 5. Format Resolution

The engine currently resolves audio through three tiers:

1. direct audio URLs already present in player responses
2. protected `signatureCipher` formats if the first-pass native solver can decode them
3. HLS manifest fallback when direct URLs are present but return `403`

Selection behavior:

- prefers `m4a` over other extensions when otherwise comparable
- then prefers higher bitrate
- iOS runtime additionally prefers Apple-friendly MP4/AAC direct audio before other container/codecs

### 6. Player JS and Protected Formats

If any audio formats have `signatureCipher`, the engine:

1. fetches the YouTube player JS from the extracted `playerUrl`
2. passes it into a platform-specific decipherer
3. attempts to resolve protected audio URLs into direct URLs

Important:

- the checked-in project does not store player JS snapshots anymore
- player JS is fetched at runtime from YouTube
- the current decipherer is heuristic, not a full JS interpreter

Current limitations:

- partial `signatureCipher` coverage only
- no robust `n` parameter solving yet
- no broad attestation/anti-bot parity

### 7. Download Phase

#### JVM

The JVM engine:

- uses `java.net.http.HttpClient`
- sends browser/client-matched headers for direct media attempts
- retries multiple candidate formats
- if all direct URLs fail, falls back to HLS and hands the manifest to the transcoder path

#### Android

The Android engine:

- uses `HttpURLConnection`
- mirrors the same direct-media retry strategy
- falls back to HLS when needed
- parses the HLS master playlist to choose an audio playlist
- downloads the HLS audio fragments locally to a temp file
- then routes that local assembled media file through Android decoding + owned native MP3 encoding

This local-fragment approach is important because Android `MediaExtractor` proved unreliable when asked to open some remote YouTube HLS playlists directly.

#### iOS

The iOS engine:

- uses `Ktor Darwin`
- mirrors the same direct-media retry strategy
- falls back to HLS when needed
- uses an owned Apple-side Objective-C/LAME bridge for MP3 transcoding
- is hosted by a real Xcode app project under `iosApp/`

### 8. MP3 Conversion

#### JVM

JVM MP3 conversion still uses a process-based ffmpeg path inside the native engine module.

This is platform-local and not a remote dependency, but it is still external tooling.

#### Android

Android conversion is owned by the project:

- `MediaExtractor` reads the local downloaded media file
- `MediaCodec` decodes audio to PCM
- JNI bridge sends PCM to bundled LAME
- encoded MP3 bytes are written to the output file

This avoids FFmpegKit entirely.

#### iOS

iOS conversion is also owned by the project:

- Objective-C wrapper uses Apple media APIs for asset reading
- owned Apple-side bridge uses vendored LAME
- direct local-file transcode is implemented in code
- HLS URL transcode path is implemented in code through the same owned bridge

## Compose Sample Behavior

The Compose sample is intentionally thin.

It does not implement downloader logic itself. It only:

- builds a `DownloadRequest`
- forwards progress/events to the UI
- lets the user pick a save directory with FileKit
- optionally moves the finished file into the selected directory
- can open the output location

### FileKit Integration

Current behavior:

- Desktop and Android can choose a save directory
- after a successful download, the app copies the final file into the chosen directory
- the original file is deleted
- the UI updates `resultPath` to the moved file
- event logs can be copied to clipboard

On Android, FileKit requires two initializations:

1. core initialization with `manualFileKitCoreInitialization(context)`
2. dialogs initialization with `FileKit.init(activity)`

This is currently hidden behind `createAndroidSampleDownloader(activity)`.

## Current Supported Reality

### Works

- JVM native download path
- Android native download path compiles, is wired to the app, and has been manually validated for direct and HLS-fallback cases
- direct media download for some videos
- HLS fallback for videos where direct URLs 403
- native-owned Android MP3 encoding module builds
- Android local HLS-fragment fallback path
- iOS engine and iOS app host compile

### Not Full Parity

- no complete `n` solver
- no full `signatureCipher` parity
- no full attestation/anti-bot handling
- no playlist/channel logic
- no subtitle/postprocessor ecosystem like `yt-dlp`

The iOS runtime path is implemented in code, but still needs broader runtime validation and hardening.

This project should currently be understood as a serious prototype and evolving library foundation, not a finished `yt-dlp` replacement.

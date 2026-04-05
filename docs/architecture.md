# Architecture

## Goal

`lizz-yt-dlp` is a provider-based Kotlin Multiplatform media client.

Today it ships with a built-in YouTube provider that supports:

- audio download
- English transcript text
- structured English transcript cues

The API and package layout are now designed so more providers can be added without reshaping the public surface again.

## Modules

- `youtube-downloader-core`: generic provider-centric public API
- `youtube-downloader-engine-youtube`: YouTube provider implementation and factories
- `android-native-media`: Android JNI/CMake bridge over vendored LAME
- `samples`: Compose Multiplatform sample module
- `androidApp`: Android sample host app
- `iosApp`: iOS sample host app

## Public API Shape

The public API lives in `dev.lizz.ytdl.core`.

Key concepts:

- `MediaClient`: the provider-aware entrypoint applications use
- `MediaProvider`: a site/provider implementation contract
- `ProviderId`: stable provider identifier such as `ProviderId.YOUTUBE`
- `AudioDownloadRequest`: provider-aware audio download request
- `TranscriptRequest`: provider-aware transcript request
- `AudioDownloadResult`: final download result including provider id
- `TranscriptResult`: structured cue-based transcript result
- `MediaEvent`: shared progress/log/result event stream

## Provider Model

Each provider implements `MediaProvider` and must be able to:

1. identify whether it can handle a locator or URL with `canHandle(...)`
2. download audio with `downloadAudio(...)`
3. return transcript text with `getTranscript(...)`
4. return structured transcript cues with `getTranscriptCues(...)`

`DefaultMediaClient` owns a list of registered providers and resolves requests by:

1. explicit `ProviderId` if supplied on the request
2. otherwise the first provider whose `canHandle(...)` returns true

## Current Provider Layout

The built-in YouTube implementation now lives under `dev.lizz.ytdl.providers.youtube`.

Important areas:

- `providers/youtube/*ProviderFactory.kt`: public platform factories
- `providers/youtube/*YoutubeProvider.kt`: platform runtime provider implementations
- `providers/youtube/YoutubeExtraction.kt`: shared watch-page + media extraction entrypoints
- `providers/youtube/watch/`: watch-page parsing, player client profiles, media resolution
- `providers/youtube/transcript/`: subtitle track selection and WebVTT parsing
- `providers/youtube/playerjs/`: protected-format JS challenge handling
- `providers/youtube/hls/`: HLS-specific helpers
- `providers/youtube/perf/`: download/performance helpers

## Audio Download Flow

For YouTube, audio download currently works like this:

1. the provider fetches and parses the watch page
2. the provider requests multiple Innertube player responses
3. audio formats are normalized and ranked, preferring audio-only streams over muxed formats
4. protected formats are resolved with the shared player-JS subsystem when possible
5. direct download is attempted first
6. if direct URLs fail:
   - JVM prefers DASH manifest fallback and then HLS
   - Android and iOS currently use HLS fallback
7. the downloaded media is transcoded to MP3 by the platform runtime

## Transcript Flow

The transcript APIs reuse the same watch-page and player-response pipeline as media downloads.

1. the provider resolves player responses
2. subtitle tracks are discovered from `captions.playerCaptionsTracklistRenderer.captionTracks`
3. the current public API selects English tracks only
4. track priority is:
   - manual `en`
   - manual `en-*`
   - automatic `en`
   - automatic `en-*`
5. the selected subtitle track is fetched as `fmt=vtt`
6. a lightweight in-memory WebVTT parser extracts cues, strips markup, decodes entities, and normalizes overlapping auto-caption cues
7. the provider returns either:
   - plain transcript text
   - structured transcript cues with timestamps

## Platform Notes

- JVM/Desktop uses `java.net.http.HttpClient` and still shells out to `ffmpeg` for MP3 conversion
- Android uses Ktor with the OkHttp engine, local HLS fragment assembly, and the owned LAME bridge
- iOS uses `Ktor Darwin`, local file writes, and the owned Apple-side LAME bridge

## Current Limits

- only the YouTube provider is implemented today
- audio download and English transcript extraction only
- partial `signatureCipher` coverage
- incomplete `n` and anti-bot handling
- JVM MP3 conversion has not yet been migrated to the owned LAME path
- transcript APIs currently select English only; no explicit language selection or translation support is exposed yet

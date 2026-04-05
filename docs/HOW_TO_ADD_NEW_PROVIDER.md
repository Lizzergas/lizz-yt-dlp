# How To Add A New Provider

This repository is now provider-based.

If you want to add support for a new source such as another media site or content platform, the goal is to implement a new `MediaProvider` without changing the public API in `youtube-downloader-core`.

## Core Principle

New providers should fit the existing public contracts:

- `MediaClient`
- `MediaProvider`
- `AudioDownloadRequest`
- `TranscriptRequest`
- `AudioDownloadResult`
- `TranscriptResult`
- `MediaEvent`

Do not add a provider by introducing a new top-level public API that bypasses `MediaClient`.

## Recommended Layout

Inside `youtube-downloader-engine-youtube`, follow the existing pattern under `src/.../dev/lizz/ytdl/providers/`.

Example target layout for a new provider named `spotify`:

```text
youtube-downloader-engine-youtube/
  src/commonMain/kotlin/dev/lizz/ytdl/providers/spotify/
    SpotifyProviderSupport.kt
    SpotifyExtraction.kt
    model/
    watch/
    transcript/
    util/
  src/jvmMain/kotlin/dev/lizz/ytdl/providers/spotify/
    JvmSpotifyProvider.kt
    JvmSpotifyProviderFactory.kt
  src/androidMain/kotlin/dev/lizz/ytdl/providers/spotify/
    AndroidSpotifyProvider.kt
    AndroidSpotifyProviderFactory.kt
  src/iosMain/kotlin/dev/lizz/ytdl/providers/spotify/
    IosSpotifyProvider.kt
    IosSpotifyProviderFactory.kt
```

Keep provider-specific logic under `providers/<name>/...`.

If a provider needs protocol helpers shared with other providers later, move those helpers into a neutral shared package instead of keeping them provider-local forever.

## Minimum Pieces To Implement

### 1. Provider support object

Add a small support object similar to `YoutubeProviderSupport.kt`.

Responsibilities:

- define a stable `ProviderId`
- implement `canHandle(locator: String)`

This should be simple and deterministic.

### 2. Provider runtime classes

For each supported runtime, add a provider class that implements `MediaProvider`.

Responsibilities:

- set `override val id`
- implement `canHandle(...)`
- implement `downloadAudio(...)`
- implement `getTranscript(...)`
- implement `getTranscriptCues(...)`

If a capability is not supported for the provider, return `null` for transcript APIs or throw a clear unsupported error only where returning `null` would be misleading.

### 3. Provider factory classes

Expose provider registration through a platform factory, for example:

```kotlin
public object JvmSpotifyProviderFactory {
    public fun createDefault(): MediaClient {
        return DefaultMediaClient(listOf(JvmSpotifyProvider()))
    }
}
```

These factories are the public entrypoints consumers use.

### 4. Shared extraction layer

Create a shared extraction entrypoint in `commonMain`.

This layer should:

- fetch provider metadata and candidate media sources
- normalize them into internal models
- keep provider-specific parsing out of platform runtime files when possible

### 5. Transcript handling

If the provider can expose transcripts, captions, lyrics, or subtitle-like data:

- normalize them into `TranscriptResult`
- keep provider-specific transcript parsing under `providers/<name>/transcript/`
- return cleaned plain text from `getTranscript(...)`
- return structured cues from `getTranscriptCues(...)`

If the provider has no transcript concept, simply return `null`.

## What To Reuse

Reuse these shared concepts instead of reinventing them:

- `DefaultMediaClient` for provider registration
- `ProviderId` for routing
- `MediaEvent` for progress and logging
- `TranscriptResult` and `TranscriptCue` for transcript data

Reuse existing provider-specific helper patterns where they fit:

- track selection
- lightweight parsing helpers
- performance helpers for throttled progress and batched downloads

## What To Avoid

- do not add provider-specific public APIs alongside `MediaClient`
- do not add file-oriented transcript APIs when in-memory APIs already fit
- do not leak provider-specific internal models into `youtube-downloader-core`
- do not put Android/iOS/JVM-only logic in `commonMain`

## Capability Guidance

Providers do not need to support every feature.

Valid shapes include:

- audio only
- transcript only
- metadata + transcript
- full audio + transcript

Implement only what the provider can do reliably.

Example:

- YouTube: audio + transcript
- another provider: transcript only
- another provider: metadata + audio, but no transcript

## Testing Requirements

When adding a new provider, add tests for at least:

1. provider URL matching
2. media or transcript selection logic
3. parser cleanup if the provider has a subtitle/transcript format
4. one happy-path runtime build for supported targets

If the public API changes:

1. update ABI baselines with the appropriate `updateKotlinAbi` tasks
2. update `docs/USAGE.md` in the same change

## Docs Requirements

A new provider PR should update:

- `README.md` if the provider becomes a public supported feature
- `docs/architecture.md`
- `docs/USAGE.md` if consumer setup or examples change
- this file if the recommended provider workflow itself changes

## Practical Workflow

1. Add `ProviderId` usage in your provider support object
2. Implement `canHandle(...)`
3. Add the shared extraction/parser layer in `commonMain`
4. Implement the runtime provider classes in `jvmMain`, `androidMain`, and `iosMain` as needed
5. Add the public platform factories
6. Add tests
7. Update docs
8. Run verification:

```bash
./gradlew check publishAllPublicationsToBuildRepo
./gradlew -p smoke-tests/kmp-consumer compileKotlinJvm -PlizzYtdlVersion=<version> -PlizzYtdlRepoPath="$(pwd)/build/repo"
```

## Suggested PR Scope

For easier review, provider contributions should ideally be split into:

1. provider scaffolding and URL matching
2. metadata/audio resolution
3. transcript support
4. sample/docs updates

That is not required, but it usually makes review much easier.

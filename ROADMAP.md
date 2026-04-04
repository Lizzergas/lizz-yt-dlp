# Roadmap

This file is the execution roadmap for turning the current prototype into a production-grade, Kotlin-native, on-device YouTube downloader library for JVM, Android, and iOS.

It focuses on four things:

1. current limitations
2. `signatureCipher` and `n` work
3. iOS compatibility and implementation path
4. `kotlinx-io` integration for cleaner, safer IO

## Current State

The project currently has:

- a KMP downloader API in `youtube-downloader-core`
- a native YouTube engine in `youtube-downloader-engine-youtube`
- JVM runtime support
- Android runtime support
- an owned Android JNI/CMake MP3 path in `android-native-media`
- sample apps for CLI, terminal, Desktop Compose, and Android Compose

The project no longer uses `yt-dlp` as a backend engine.

## Current Limitations

### YouTube Extraction Parity

The biggest limitations are in the native YouTube engine.

Current engine files:

- shared extraction core:
  - `youtube-downloader-engine-youtube/src/commonMain/kotlin/com/lizz/ytdl/engine/youtube/YoutubeExtraction.kt`
- JVM runtime engine:
  - `youtube-downloader-engine-youtube/src/jvmMain/kotlin/com/lizz/ytdl/engine/youtube/JvmNativeYoutubeDownloadEngine.kt`
- Android runtime engine:
  - `youtube-downloader-engine-youtube/src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidNativeYoutubeDownloadEngine.kt`

Current gaps:

- partial `signatureCipher` support only
- no robust `n` parameter solving
- no broad anti-bot / attestation handling
- no robust DASH handling path for audio
- no playlist/channel support
- no subtitle/thumbnails/postprocessors beyond MP3 generation
- heavy reliance on iOS client responses and HLS fallback for some videos

### Engine Structure

The extraction logic works, but it is too concentrated.

Current issue:

- `YoutubeExtraction.kt` contains too many responsibilities at once:
  - URL parsing
  - watch-page parsing
  - Innertube client definitions
  - format resolution
  - manifest extraction
  - player JS URL extraction

This makes future parity work slower and harder to test.

### Android Runtime Risks

Android currently compiles and is wired, but still needs runtime hardening.

Main Android files:

- downloader:
  - `youtube-downloader-engine-youtube/src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidNativeYoutubeDownloadEngine.kt`
- player JS decipherer:
  - `youtube-downloader-engine-youtube/src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidPlayerJsDecipherer.kt`
- transcoder:
  - `youtube-downloader-engine-youtube/src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidMp3Transcoder.kt`

Current Android risks:

- runtime behavior of `MediaExtractor.setDataSource(manifestUrl, headers)` across vendors
- PCM format/channel assumptions in `AndroidMp3Transcoder`
- file reveal/open-directory behavior with FileKit and Android file providers
- no instrumentation tests yet for the full Android path

### iOS

iOS builds for shared code, but there is no real iOS downloader/transcoder runtime yet.

Current iOS sample file:

- `sample-compose/src/iosMain/kotlin/com/lizz/ytdl/sample/compose/IosSampleDownloader.kt`

Current status:

- shared modules compile for iOS targets
- Compose sample has an iOS placeholder
- there is no iOS app target in this repository yet
- there is no iOS-native downloader implementation yet
- there is no iOS-native MP3 transcoder yet

### IO Model

Current runtime IO is mixed:

- JVM uses `java.net.http.HttpClient` and `java.nio.file`
- Android uses `HttpURLConnection`, `File`, and raw streams
- shared code is still mostly string/JSON parsing plus abstract models

This is workable, but it makes the engines more divergent than they should be.

`kotlinx-io` should be used to converge file and stream handling where practical.

## Roadmap By Workstream

## 1. `signatureCipher` Work

### Goal

Convert protected YouTube media formats into usable direct URLs without relying on external JS runtimes or server-side processing.

### Current files

- JVM decipherer:
  - `youtube-downloader-engine-youtube/src/jvmMain/kotlin/com/lizz/ytdl/engine/youtube/JvmPlayerJsDecipherer.kt`
- Android decipherer:
  - `youtube-downloader-engine-youtube/src/androidMain/kotlin/com/lizz/ytdl/engine/youtube/AndroidPlayerJsDecipherer.kt`
- format model carrying cipher info:
  - `YoutubeExtraction.kt` -> `NativeAudioFormat.signatureCipher`

### Current limitation

The decipherers are simple heuristic pattern matchers.

They currently:

- look for a function that splits the input string
- identify a helper object
- map helper methods to a small set of operations:
  - reverse
  - drop
  - swap

This is not enough for broad parity.

### What to do next

#### Step 1: Move deciphering to shared common code

Target new files:

- `youtube-downloader-engine-youtube/src/commonMain/kotlin/com/lizz/ytdl/engine/youtube/playerjs/PlayerJsFetcher.kt`
- `.../playerjs/SignatureFunctionLocator.kt`
- `.../playerjs/SignatureOperation.kt`
- `.../playerjs/SignatureTransformParser.kt`
- `.../playerjs/SignatureTransformExecutor.kt`

Reason:

- JVM and Android decipherers are nearly identical
- this logic should not be duplicated by platform

#### Step 2: Expand transform detection

Support more helper method shapes than the current three operations.

Specifically add support for:

- slice-based drops with different syntactic forms
- swap variants using temp variables or array indexing aliases
- nested helper object references
- helper methods defined outside the immediate object literal

#### Step 3: Stop relying on one regex family

Function discovery should support:

- `name=function(a){...}`
- `function name(a){...}`
- minified helper references with alternate arg names
- function bodies with extra noise before/after the transform sequence

#### Step 4: Add fixture-driven tests

Create fixtures for:

- watch pages
- player JS files
- protected format entries
- expected deciphered signature output

Suggested new test fixtures layout:

```text
youtube-downloader-engine-youtube/src/commonTest/resources/fixtures/youtube/
  watch/
  player/
  signature/
```

#### Step 5: Introduce a dedicated protected-format resolution stage

Right now protected-format handling is embedded inside the runtime engines.

Create a shared `ProtectedFormatResolver` that:

1. receives `ResolvedYoutubeMedia`
2. fetches player JS
3. resolves protected formats
4. returns updated candidate formats

This should be called by JVM/Android engines, not reimplemented by them.

## 2. `n` Parameter Work

### Goal

Support URLs that require YouTube’s `n` throttling transformation so the engine can use more direct media paths rather than falling back to HLS.

### Current state

There is no robust `n` support yet.

This is one of the main reasons the native engine still depends heavily on HLS fallback.

### What to build

Add a dedicated `n` solving subsystem in shared code.

Suggested files:

- `youtube-downloader-engine-youtube/src/commonMain/kotlin/com/lizz/ytdl/engine/youtube/playerjs/NFunctionLocator.kt`
- `.../playerjs/NTransformParser.kt`
- `.../playerjs/NTransformExecutor.kt`
- `.../playerjs/UrlQueryRewriter.kt`

### Implementation plan

#### Step 1: detect `n` in candidate URLs

Before download attempts, inspect query parameters for `n`.

If present:

- fetch player JS if not already fetched
- locate the `n` transform function
- compute rewritten `n`
- rebuild the media URL

#### Step 2: separate `n` from `signatureCipher`

Do not mix `n` rewriting into the signature solver.

They are related but distinct:

- signature solving reconstructs missing auth/signature query parameters
- `n` solving rewrites an existing throttling parameter

#### Step 3: test with known URLs

Store fixture cases where:

- direct media URL fails without `n` rewrite
- direct media URL succeeds after `n` rewrite

## 3. iOS Compatibility

### Goal

Make the native engine truly multiplatform at runtime, not just compile-time, by implementing an iOS downloader/transcoder path.

### Current state

Shared modules already target iOS:

- `youtube-downloader-core`
- `youtube-downloader-engine-youtube`
- `sample-compose`

What is missing:

- iOS app host
- iOS sample downloader actual implementation
- iOS HTTP/download runtime implementation
- iOS MP3 transcoder

### Recommended architecture

#### Step 1: Add an iOS app module

Create a dedicated iOS app host, separate from shared code.

Keep the current structure principle that already exists for Android:

- shared KMP code in shared modules
- platform entrypoint in a platform app module

#### Step 2: Implement an iOS downloader in `iosMain`

Suggested files:

- `youtube-downloader-engine-youtube/src/iosMain/kotlin/com/lizz/ytdl/engine/youtube/IosNativeYoutubeDownloaderFactory.kt`
- `.../IosNativeYoutubeDownloadEngine.kt`

Use:

- `NSURLSession`-backed networking through Ktor or platform wrappers
- shared extraction logic from `commonMain`
- platform-specific file writes into app Documents/Caches

#### Step 3: Implement iOS MP3 support

There is no built-in “easy MP3 encoder” path on iOS equivalent to the Android JNI bridge we now own.

Recommended approach:

1. vendor LAME source for Apple targets too
2. create a Kotlin/Native cinterop layer or Objective-C/Swift wrapper over LAME
3. decode media to PCM using Apple media APIs
4. feed PCM into the owned MP3 encoder

Suggested future module split if this grows:

- `apple-native-media/`
  - shared vendored encoder source or Apple-specific wrapper

#### Step 4: FileKit on iOS

Use FileKit in the Compose sample for:

- save directory selection where allowed by platform UX
- opening the result file/directory

Be careful with iOS security-scoped access for user-selected files.

### Short-term iOS milestone

The shortest realistic iOS path is:

1. direct/HLS download to app sandbox
2. no custom save-directory UX initially
3. MP3 output into app-private location
4. file reveal/share UX after download

Then add picker-based save/export behavior later.

## 4. `kotlinx-io` Integration

### Goal

Reduce platform drift in file and stream handling and make IO code easier to reason about across JVM, Android, and iOS.

### Current problem

Current engines use platform-native IO APIs directly:

- JVM: `Path`, `Files`, `InputStream`
- Android: `File`, `InputStream`, `HttpURLConnection`

This makes logic sharing harder and encourages duplicated platform code.

### Recommended approach

#### Step 1: introduce shared IO abstractions

Add a small shared IO layer in `youtube-downloader-core` or `youtube-downloader-engine-youtube`.

Suggested files:

- `io/PlatformFileRef.kt`
- `io/BinarySource.kt`
- `io/BinarySink.kt`
- `io/FileSystemAdapter.kt`

#### Step 2: use `kotlinx-io` for stream copying and buffering

Start with the least risky places:

- copying downloaded files into FileKit-selected destinations
- buffering output writes
- common utility functions for reading byte arrays / strings from streams

#### Step 3: migrate shared helper logic first

Do not rewrite the entire engine in one pass.

Migrate these first:

- file copy utilities
- temp output handling
- write buffering
- text decoding helpers

#### Step 4: evaluate replacing platform-specific stream loops

Once helpers exist, gradually replace repeated manual loops in:

- `JvmNativeYoutubeDownloadEngine`
- `AndroidNativeYoutubeDownloadEngine`

This should happen after tests are in place.

### Expected benefits

- more shared IO behavior
- easier future iOS integration
- less duplicated file logic
- clearer move/copy/temp-file pipeline

## Recommended Sequence From Here

### Phase A: harden current engine

1. add tests for current JVM and Android flows
2. add fixtures for watch pages, player responses, and player JS
3. stabilize direct/HLS/fallback logic

### Phase B: shared protected-format subsystem

1. move `signatureCipher` logic into `commonMain`
2. implement robust `n` handling
3. increase direct URL success rate before HLS fallback

### Phase C: iOS runtime support

1. add iOS app host
2. add iOS downloader implementation
3. add iOS MP3 encoding path
4. wire Compose sample for iOS

### Phase D: IO cleanup

1. adopt `kotlinx-io` incrementally
2. unify shared stream/file helpers
3. remove duplicated platform copy/write code

## Immediate TODO List

Highest-value next tasks:

1. split `YoutubeExtraction.kt` into smaller files
2. move decipherer logic to shared code
3. add `n` solving support
4. add fixture-based tests for protected formats
5. runtime-test Android downloader/transcoder on real device and emulator
6. create iOS app target and real iOS downloader skeleton
7. begin `kotlinx-io` helper layer for shared file/stream operations

# Findings And Reimplementation Guide

## Executive Summary

This project proved that a Kotlin-native, on-device YouTube audio downloader is feasible enough to prototype, but it also proved that a full `yt-dlp` rewrite is a large systems project.

The biggest lessons are not about syntax or Gradle. They are about strategy:

1. YouTube extraction is the hard problem
2. media transcoding is the second hard problem
3. platform structure matters early, especially with AGP 9+
4. UI and sample apps should remain thin wrappers over the engine

## Main Findings

### 1. The watch page is not enough

The watch page can provide:

- `ytcfg`
- initial player response
- player JS URL

But it is not sufficient on its own for broad parity.

You must also handle:

- multiple Innertube clients
- direct URLs vs ciphered URLs
- HLS/DASH manifests
- player JS transform discovery
- anti-bot behavior differences by client

### 2. Different clients expose different truths

The engine currently demonstrates a core `yt-dlp` lesson:

- `web`, `ios`, `tv`, and `android` do not expose the same media landscape

Observed behavior in this project:

- `web` often returns no `streamingData`
- `ios` often gives useful audio data, but direct URLs may still 403
- `android` can be unavailable or return 400 in some cases
- HLS fallback is often what makes a download actually finish

### 3. “Direct URL exists” does not mean “download will work”

The prototype hit this repeatedly.

Even when a player response contains direct media URLs:

- they may require client-matched headers
- they may still 403
- a manifest may be the actual usable path

That means selection logic cannot stop at “has a URL”. It must include:

- request shaping
- retry strategy
- fallback ordering

It also means platform-specific runtime behavior matters. In this project, Android ultimately needed a downloader-style local HLS fragment path rather than relying on the platform media stack to open remote HLS playlists directly.

### 4. Partial deciphering is useful but not enough

The current player-JS decipherers are intentionally minimal.

They help establish the architecture:

- fetch player JS
- parse transform function shape
- map helper methods to Kotlin operations

But full parity requires:

- more transform patterns
- `n` parameter support
- stronger function discovery
- better resilience to player JS changes

### 5. Android MP3 is not “just use a library”

The archived FFmpegKit detour confirmed the real constraint:

- if ownership and long-term compatibility matter, you must own the native path

The current Android solution is better strategically because it:

- owns the JNI layer
- owns the LAME bridge
- avoids relying on abandoned wrappers

An additional Android lesson came later:

- some HLS playback problems are actually downloader problems

The working Android strategy became:

1. parse HLS master manifest
2. select audio playlist
3. download segments locally
4. decode local assembled media
5. encode MP3

### 6. AGP 9 forces the right structure

The AGP 9 migration forced an architectural cleanup that was worth doing anyway:

- Android app entrypoint isolated in `androidApp`
- shared code kept in KMP modules
- Compose sample shared from a library-style module

This is the correct long-term structure for Android/iOS/Desktop parity.

### 7. iOS host structure matters too

The project initially only had shared iOS targets. That was not enough for a smooth Compose Multiplatform workflow.

The correct iOS setup needed:

- a shared KMP/Compose module exporting an Apple framework
- a real `iosApp/iosApp.xcodeproj` host
- IDE metadata pointing Android Studio to the Xcode host

Without that, iOS support exists only on paper.

## What Would Have Saved Time

If this project were restarted, the fastest sane path would be:

### Phase 1: structure first

Set up from day one:

- `core`
- `engine-youtube`
- `sample-cli`
- `sample-compose`
- `androidApp`
- `android-native-media`

Doing this earlier would have avoided several refactors.

### Phase 2: CLI first, not UI first

The fastest path to validating engine behavior is a CLI.

The actual high-leverage test surface is:

- one command
- verbose events
- JSON progress option

That is why `sample-cli` ended up being more useful than the terminal UI for engine work.

### Phase 3: build around HLS fallback earlier

A faster implementation should have assumed from the start:

- direct URLs are opportunistic
- HLS is often the real path

That would have reduced time spent treating direct URL failure as exceptional rather than expected.

For Android specifically, it would also have helped to assume early that remote HLS playback might fail and that a local fragment downloader could be necessary.

### Phase 4: design the player-JS pipeline as a subsystem

Instead of a small decipherer file growing organically, start with explicit components:

- `PlayerJsFetcher`
- `PlayerJsFunctionLocator`
- `SignatureTransformParser`
- `SignatureTransformExecutor`
- `NTransformParser`
- `NTransformExecutor`

This would make the later parity work much easier.

### Phase 5: own Android native media from the start

The fastest long-term path would have skipped any wrapper exploration and gone directly to:

- vendored source archive
- Gradle extraction task
- JNI bridge
- platform decoder + owned encoder

That is the approach now in place.

## Recommended Reimplementation Sequence

If someone were to rebuild this project from scratch with the current knowledge, do it in this order.

### Step 1

Create only three things first:

- public KMP API module
- native YouTube engine module
- CLI sample

Do not start with terminal UI or Compose UI.

### Step 2

Implement metadata + format resolution only:

- URL parse
- watch page fetch
- `ytcfg`
- initial player response
- Innertube fallback clients
- format normalization

Goal:

- return a structured `ResolvedYoutubeMedia`
- no download yet

### Step 3

Implement real download fallback order:

1. direct URL candidates
2. protected direct URL candidates after JS solving
3. HLS manifest fallback

Do not assume one path will win globally.

### Step 4

Add platform MP3 conversion separately.

Keep extraction/download and transcoding as different subsystems.

### Step 5

Only then add UI layers:

- Compose sample
- Android app
- iOS app

## Recommended Future Refactors

### 1. Split `YoutubeExtraction.kt`

It currently contains too much knowledge in one place.

Break it into:

- `YoutubeUrlParser.kt`
- `YoutubeWatchPageParser.kt`
- `YoutubeInnertubeClients.kt`
- `YoutubeFormatResolver.kt`
- `YoutubePlayerJsLocator.kt`

### 2. Unify decipherer implementations

JVM and Android currently have separate but almost identical decipherer logic.

That should become shared logic in `commonMain` if possible.

### 3. Separate downloader from extractor more cleanly

The runtime engines currently interleave:

- extraction
- candidate selection
- direct download logic
- fallback logic

Introduce explicit phases and objects for each.

### 4. Add fixtures early

The next major acceleration step is a fixture corpus:

- watch pages
- player responses
- player JS files
- expected resolved signatures
- expected selected formats
- representative HLS master/audio playlists
- representative local-fragment assembly cases

Without that, every parity improvement becomes slower and riskier.

## What Is Still The Biggest Unknown

The hardest unfinished problem is still not Android transcoding.

It is broad YouTube parity for:

- `signatureCipher`
- `n`
- client differences
- anti-bot / attestation

That is the part most likely to require continuous maintenance.

The biggest platform-specific unfinished area is now broad iOS runtime validation, not iOS build structure.

## Practical Advice For Continuing This Project

1. Treat the engine as a protocol emulator, not a downloader
2. Keep the UI dumb
3. Prefer fixture-driven work over manual trial-and-error
4. Expect client behavior to change over time
5. Keep third-party ownership explicit, as done with the LAME tarball approach
6. Do not let temporary debug artifacts enter the repo
7. Keep Android and iOS app entrypoints isolated from shared modules

## Short Version

If rebuilding this project for speed, do this:

1. KMP API + native engine + CLI only
2. solve watch page + Innertube + HLS first
3. add JS deciphering as a dedicated subsystem
4. add owned Android native media module early
5. add Compose/Android/iOS samples last

That would have saved the most time.

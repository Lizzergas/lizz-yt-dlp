# Android Native Media

## Why This Exists

Android needed an MP3 path that the project owns.

Constraints:

- do not depend on archived FFmpegKit
- do not shell out to external tools on device
- keep the media stack inside the project
- support 16 KB page-size compatibility on Android

The result is a dedicated native module:

- `android-native-media`

## Design

The Android media stack is split into two pieces:

### Kotlin side

- `AndroidMp3Transcoder.kt`
- `LameEncoderBridge.kt`

Responsibilities:

- use Android media APIs to decode compressed audio to PCM
- feed PCM into JNI-backed LAME
- write MP3 bytes to the destination file

### Native side

- `android-native-media/src/main/cpp/ytdl_android_media.cpp`
- `android-native-media/src/main/cpp/CMakeLists.txt`

Responsibilities:

- initialize and configure LAME
- encode interleaved PCM samples
- flush final MP3 frames
- release encoder state

## Third-Party Source Strategy

The project vendors the LAME source archive only:

- `third_party/lame-3.100.tar.gz`

It does **not** keep the unpacked tree in git.

During build:

1. Gradle task `prepareLameSource` extracts the tarball into `android-native-media/build/third_party/`
2. CMake receives `LAME_ROOT` as a build argument
3. CMake compiles the required `libmp3lame/*.c` files into the shared library

This keeps the git history smaller and makes the third-party ownership explicit.

## Did We Modify LAME?

No.

Current status:

- no local source modifications were made to vendored LAME
- all project-specific behavior lives in our own wrapper code:
  - JNI bridge
  - CMake build
  - Kotlin transcoder

## JNI Surface

`LameEncoderBridge` exposes four native operations:

- `nativeInit`
- `nativeEncodeInterleaved`
- `nativeFlush`
- `nativeClose`

The Kotlin side owns lifecycle through `AutoCloseable`.

## Android Transcoding Pipeline

Implemented in `AndroidMp3Transcoder.kt`.

### File source path

1. `MediaExtractor.setDataSource(inputFile.absolutePath)`
2. select first audio track
3. `MediaCodec` decodes compressed audio to PCM
4. PCM is converted from byte buffer to `ShortArray`
5. JNI bridge encodes to MP3
6. MP3 bytes are streamed to output file

### HLS manifest path

The original remote-manifest approach proved unreliable on Android for some YouTube URLs.

Current path:

1. fetch HLS master manifest
2. parse `#EXT-X-MEDIA` entries and choose an audio playlist
3. fetch the selected audio playlist
4. download its media segments locally into a temp media file
5. run the normal local-file decode-to-PCM path against that temp file
6. same JNI LAME encode path as above

This is much closer to a fragment downloader like `yt-dlp` and is more robust than asking Android `MediaExtractor` to open some remote YouTube HLS playlists directly.

## 16 KB Page Size Compatibility

The native library uses:

```cmake
target_link_options(
    ytdl_android_media
    PRIVATE
    -Wl,-z,max-page-size=16384
)
```

This is the project-level step we added toward 16 KB page-size compatibility.

Note:

- compatibility still depends on the full native toolchain and transitive native artifacts
- currently we only own this one native library, so this is manageable

## ABI Strategy

Current ABI filters:

- `arm64-v8a`
- `x86_64`

That matches a practical modern setup for:

- physical 64-bit Android devices
- 64-bit emulators

## Current Risks

### 1. Decoder assumptions

The current `AndroidMp3Transcoder` assumes:

- the decoded PCM can be treated as interleaved 16-bit little-endian samples
- channel handling is effectively stereo-oriented in the current implementation

This is workable for a prototype but should be hardened.

### 2. Manifest handling

The direct remote-manifest path is no longer the main Android strategy because it proved unreliable.

The current Android risks are now:

- local HLS playlist parsing robustness
- segment download correctness
- local fragment concatenation assumptions
- final local temp-file decodability across devices/vendors

### 3. LAME warnings

The native build produces warnings from upstream LAME sources.

They are currently warnings only, not build failures.

Because we are intentionally not patching vendored LAME yet, the current policy is:

- accept warnings
- keep our wrapper code small
- patch upstream source only if runtime behavior requires it

## Why Not Use FFmpegKit

We explicitly rejected FFmpegKit because:

- it is archived
- it increases supply-chain risk
- it conflicts with the goal of owning the native stack
- it weakens long-term control over 16 KB page-size support

## What To Harden Next

1. runtime test on physical Android device and emulator
2. verify mono vs stereo handling
3. validate sample rate/bitrate assumptions for more source formats
4. add instrumentation-level transcoder tests
5. make output integrity checks explicit
6. eventually replace ad-hoc JNI API with a more formal transcoder abstraction in shared code
7. add tests specifically for the local HLS fragment downloader path

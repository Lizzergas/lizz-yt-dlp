# Android Native Media

`android-native-media` owns the Android MP3 encoder path.

## Why It Exists

- no FFmpegKit dependency
- no shelling out on device
- controlled native stack for Android media work

## Structure

- Kotlin side: `AndroidMp3Transcoder.kt`, `LameEncoderBridge.kt`
- Native side: `ytdl_android_media.cpp`, `CMakeLists.txt`
- Third-party source: `third_party/lame-3.100.tar.gz`

## Build Model

1. `prepareLameSource` extracts the vendored archive into the module build directory.
2. CMake compiles the required `libmp3lame` sources.
3. The Android engine decodes media to PCM and sends it through JNI to LAME.

## Current Notes

- supported ABIs: `arm64-v8a`, `x86_64`
- the native linker is configured for 16 KB page-size compatibility
- upstream LAME sources are vendored without local modifications

# Changelog

## 0.1.0-alpha04

- refactor the YouTube engine around a shared probe, planning, retry, and cache layer so JVM, Android, and iOS follow the same extraction pipeline
- improve YouTube stability and performance with concurrent Innertube client probing, centralized transcript track selection, and better English regional fallback
- split the JVM mp3 conversion path into a dedicated transcoder class while keeping `ffmpeg`-based behavior unchanged
- update architecture and release docs, and fix CI/release smoke tests to read the current version instead of a stale hardcoded alpha

## 0.1.0-alpha03

- add transcript APIs for plain English transcript text and structured transcript cues across JVM, Android, and iOS
- improve direct YouTube format selection to prefer audio-only streams and update Android client selection to avoid slow HLS fallback more often
- update the sample app with transcript and cue viewing flows plus transcript formatting controls

## 0.1.0-alpha02

- fix Android and iOS HLS audio fallback assembly for segmented AAC streams by stripping leading ID3 metadata before local transcode
- clean up release publishing and signing flow for Sonatype Central Portal

## 0.1.0-alpha01

- first Maven Central alpha for `lizz-yt-dlp`
- added publishable KMP module conventions, signing, and release workflows
- added ABI validation, smoke-test consumer project, and release documentation
- cleaned the public API to remove unused options and added KDoc for published types

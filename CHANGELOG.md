# Changelog

## 0.1.0-alpha02

- fix Android and iOS HLS audio fallback assembly for segmented AAC streams by stripping leading ID3 metadata before local transcode
- clean up release publishing and signing flow for Sonatype Central Portal

## 0.1.0-alpha01

- first Maven Central alpha for `lizz-yt-dlp`
- added publishable KMP module conventions, signing, and release workflows
- added ABI validation, smoke-test consumer project, and release documentation
- cleaned the public API to remove unused options and added KDoc for published types

# Third-Party Notices

## LAME 3.100

- Source archive: `third_party/lame-3.100.tar.gz`
- Upstream files included in the archive: `LICENSE`, `COPYING`, `README`
- Usage in this repository: Android and Apple MP3 encoding bridges

The vendored LAME archive is redistributed intact. This repository does not keep
an unpacked copy of the source tree in git.

Project-specific behavior lives in repository-owned wrapper code such as the JNI,
CMake, and Objective-C bridge layers.

Review the upstream license texts shipped inside the vendored archive before
redistributing published artifacts that include this third-party code.

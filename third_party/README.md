# Third-Party Archives

This project keeps vendored third-party code as source archives, not as unpacked source trees.

Currently tracked:

- `lame-3.100.tar.gz`

The Android native-media module extracts the archive automatically into its own `build/third_party/` directory during the Gradle build. You do not need to unpack it manually for normal builds.

Notes:

- The unpacked directory `third_party/lame-3.100/` should not be committed.
- It is intentionally listed in `.gitignore`.
- The build consumes the archive and unpacks it into module-local build output.

If you already have the unpacked directory tracked in git, remove it from the index once:

```bash
git rm -r --cached third_party/lame-3.100
```

Then commit the removal while keeping `third_party/lame-3.100.tar.gz` in the repository.

Important:

- No local source modifications were made to LAME itself.
- Any project-specific changes live in our own JNI/CMake wrapper code under `android-native-media/`.

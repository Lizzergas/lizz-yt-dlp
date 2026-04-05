# Release Checklist

1. Run `./gradlew check publishAllPublicationsToBuildRepo`.
2. Run the smoke test consumer against `build/repo`.
3. Review `CHANGELOG.md` and bump `version` in `gradle.properties` if needed.
4. Review `docs/USAGE.md` and update it in the same change if public behavior, setup, or examples changed.
5. If the public API changed, run `:youtube-downloader-core:updateKotlinAbi` and any other required ABI update tasks before release.
6. Verify `README.md`, `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` are current.
7. Create or update the release tag, for example `v<version>`.
8. Push the tag to trigger `.github/workflows/release.yml`.
9. Ensure the workflow publishes through Sonatype Central Portal successfully.
10. Confirm the release appears in Sonatype deployments and then on Maven Central.
11. Announce the published coordinates and any known limitations.

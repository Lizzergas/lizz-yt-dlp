# Release Checklist

1. Run `./gradlew check publishAllPublicationsToBuildRepo`.
2. Run the smoke test consumer against `build/repo`.
3. Review `CHANGELOG.md` and bump `version` in `gradle.properties` if needed.
4. Verify `README.md`, `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` are current.
5. Create or update the release tag, for example `v0.1.0-alpha01`.
6. Trigger `.github/workflows/release.yml` with valid `OSSRH_*` and signing secrets.
7. Confirm the release appears on Maven Central.
8. Announce the published coordinates and any known limitations.

# Google Play phone screenshots

These five 1080 x 1920 RGB PNG files were captured from the minified `qa` build on the clean `VSlot_API36_Clean` API 36 AVD on 2026-09-01. They show only real release-reachable application states driven by instrumentation tests; no interface layer was composed or retouched after capture.

Reproduce the set from the repository root with:

```bash
tools/capture_play_store_screenshots.sh EMULATOR_SERIAL
```

The AVD must expose an unmodified 1080 x 2400 display at 420 dpi. The script temporarily configures a 1080 x 1920 viewport at 360 dpi so the complete phone UI fits the recommended 9:16 frame, selects the `ru-RU` listing locale, fixes the font scale and orientation, runs the two selected smoke tests, pulls exactly five screenshots, validates their geometry, and restores the original display size, density, and locale.

`capture-metadata.json` records the QA package/version, exact signed and canonical payload SHA-256 values for both the minified app APK and its instrumentation APK, Android 36 AVD profile, capture geometry, locale, and SHA-256 of every PNG. `verifyStoreAssets` recalculates the screenshot hashes and rejects a stale or modified set. `verifyStoreScreenshotsAgainstQaApk` rebuilds both APKs and requires their canonical payload hashes to match the capture metadata; this remains reproducible when local and CI debug signing certificates differ.

The metadata intentionally does not embed a commit hash. Capture the reviewed set before the release commit, commit the PNG files and metadata together, then run the artifact verifier from the clean committed revision. `verifyStoreReadiness` requires every screenshot and the metadata file to exist unchanged in release `HEAD`, while the rebuilt app and instrumentation payload hashes prove that the committed sources produce the binaries used by the capture tests. This avoids a circular requirement where writing `HEAD` into a tracked file would create a new `HEAD`.

Before a Play Console upload, visually review every selected image against the final release build. At least two screenshots are required; this set provides home, base slot, paytable, settings and safety disclosure, and free-spins coverage. Do not add policy copy as a post-capture overlay: the Russian `18+`, virtual-coins, no-purchases, no-real-money, and no-prizes disclosure is already visible in the real settings state and repeated in the listing text.

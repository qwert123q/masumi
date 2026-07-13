# Masumi

Masumi is an Android-first manga localization project. The current foundation imports a chapter and runs resumable on-device page detection without modifying the source files.

This repository is at an early stage. OCR, translation, artwork cleanup, typesetting, and final image export are not implemented yet; detection artifacts are the verified input boundary for those later stages.

## Current capability

- Select a folder through Android's system document picker.
- Import direct-child JPEG, PNG, and WebP pages in natural filename order.
- Stream each page once while computing its SHA-256 content identifier.
- Store source objects, a versioned project manifest, and JSON/text task reports.
- Publish a project only after every artifact has been staged successfully.
- Acquire and verify a revision- and SHA-pinned comic detector package.
- Run the local ONNX detector sequentially in a foreground service.
- Retain all 300 model queries while separating dialogue-box candidates, in-box text, and protected free text.
- Retry one failed page with a rebuilt detector session, then preserve its source and continue.
- Resume cancelled or interrupted work from committed page checkpoints.
- Publish versioned region JSON, annotated PNG previews, and a terminal report atomically.
- Navigate completed previews in manifest order inside the app.
- Keep debug and future release installations separate.

## Modules

- `:app` contains Android document access, bitmap/EXIF preparation, ONNX Runtime, foreground execution, and the import/progress/preview UI.
- `:pipeline-core` contains portable import and detection contracts, deterministic identities, post-processing, state transitions, model-package integrity, reporting, and atomic publication.

## Build and test

Requirements: JDK 17 and an Android SDK with platform and build tools 36.

```bash
./gradlew :pipeline-core:test
./gradlew :app:assembleDebug
```

With an Android device or emulator connected:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner
```

The commands replace-install both packages and do not uninstall application data.

See [the foundation architecture](docs/architecture/foundation.md) for the project artifact contract and failure semantics.

## License

GPL-3.0-only. See [LICENSE](LICENSE).

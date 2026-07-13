# Masumi

Masumi is an Android-first manga localization project. The current foundation release imports a chapter folder into a durable, inspectable project without modifying the source files.

This repository is at an early stage. OCR, translation, artwork cleanup, typesetting, and final image export are not implemented yet.

## Current capability

- Select a folder through Android's system document picker.
- Import direct-child JPEG, PNG, and WebP pages in natural filename order.
- Stream each page once while computing its SHA-256 content identifier.
- Store source objects, a versioned project manifest, and JSON/text task reports.
- Publish a project only after every artifact has been staged successfully.
- Keep debug and future release installations separate.

## Modules

- `:app` contains the Android document adapter and the minimal import screen.
- `:pipeline-core` contains platform-neutral selection, hashing, project layout, serialization, reporting, and atomic publication logic.

## Build and test

Requirements: JDK 17 and an Android SDK with platform and build tools 36.

```bash
./gradlew :pipeline-core:test
./gradlew :app:assembleDebug
```

With an Android device or emulator connected:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

See [the foundation architecture](docs/architecture/foundation.md) for the project artifact contract and failure semantics.

## License

GPL-3.0-only. See [LICENSE](LICENSE).

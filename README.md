# Masumi

Masumi is an Android-first manga localization project. The current foundation imports a chapter, detects comic text regions, recognizes them locally with PaddleOCR-VL, translates trusted Japanese text into Simplified Chinese, and produces source-text-cleaned page images without modifying the source files.

This repository is at an early stage. Conservative source-text cleanup is implemented; Chinese typesetting and final image export are not implemented yet. Versioned cleaned-page artifacts are now the verified boundary for those later stages.

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
- Acquire and verify the pinned PaddleOCR-VL 1.6 GGUF model and multimodal projector (about 1.82 GB combined).
- Consolidate overlapping text proposals, associate bubble context, and assign deterministic Japanese reading order.
- Skip only low-confidence free-text candidates whose page-relative geometry is clearly implausible, while never filtering in-box dialogue at this gate.
- Normalize canonical BF16 GGUF files to verified F16 during installation, then run sequential arm64 Vulkan-preferred OCR with deterministic CPU fallback.
- Size the vision workload from each real crop's width, height, and aspect ratio within a quality-tested adaptive range instead of forcing one fixed text-box size.
- Try up to three deterministic crops per region and accept text only when token quality or cross-crop agreement passes the recorded policy.
- Preserve the original artwork for uncertain or failed regions instead of publishing guessed text.
- Checkpoint every terminal region, resume cancellation or interruption without repeating committed regions, and publish strict OCR JSON, previews, and a report atomically.
- Review recognized text and protected regions page by page inside the app; no manual approval is required to finish a run.
- Convert terminal OCR pages into strict, ordered translation inputs while carrying uncertain regions forward as protected artwork.
- Build deterministic chapter translation windows with bounded context and validate structured model output by stable region ID so one malformed item cannot discard valid siblings.
- Call OpenAI-compatible translation endpoints through a cancellable OkHttp boundary with bounded transient retries, strict structured-response parsing, safe errors, and token-usage capture.
- Checkpoint translation windows and pages atomically, recover only an interrupted active window, and publish strict page results, final glossary, usage totals, and protection report as one versioned run.
- Save provider credentials only in application-private settings, then start, cancel, monitor, or resume whole-chapter translation from the Android UI without rerunning OCR.
- Clean only regions with accepted translations, using local background fill for bubble text and boundary-propagated inpainting for translated free text.
- Reject empty or unsafe glyph masks, preserve protected regions and complete failed pages, and record every cleanup outcome without manual approval.
- Checkpoint cleaned PNG and strict page JSON together, resume interrupted pages without repeating OCR or translation, and preview the published result in the app.
- Keep debug and future release installations separate.

## Modules

- `:app` contains Android document access, bitmap/EXIF preparation, ONNX Runtime, the arm64 llama.cpp/`mtmd` bridge, cleanup pixels, foreground execution, and the import/detection/OCR/translation/cleanup UI.
- `:pipeline-core` contains portable import, detection, OCR, translation, and cleanup contracts; deterministic identities; candidate/quality policy; state transitions; model-package integrity; reporting; and atomic publication.

## Build and test

Requirements: JDK 17, an Android SDK with platform and build tools 36, the Android NDK, CMake, and Git submodules initialized. The OCR native build currently targets `arm64-v8a`.

```bash
git submodule update --init --recursive
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

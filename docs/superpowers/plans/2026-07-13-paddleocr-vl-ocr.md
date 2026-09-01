# PaddleOCR-VL OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a published detection run into a resumable, on-device PaddleOCR-VL 1.6 OCR artifact with deterministic region consolidation, protected uncertainty, previews, and no source mutation.

**Architecture:** Portable OCR contracts, identity, consolidation, quality policy, state, and storage live in `pipeline-core`. Android owns model installation, bitmap crops, foreground orchestration, UI, and a narrow `OcrEngine`; a pinned arm64 JNI library owns llama.cpp `mtmd` model loading and crop inference. Region checkpoints are committed independently and a complete OCR run is atomically published.

**Tech Stack:** Kotlin 2.3, kotlinx.serialization, Android SDK 36, Android NDK/CMake, llama.cpp `b8935`, JNI/C++17, GGUF/mtmd, JUnit 4, Android instrumentation.

---

## File map

### Portable OCR core

- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt`: strict serializable OCR, model, job, page, region, report, and quality records.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrIdentity.kt`: opaque OCR run/page IDs and structural region IDs.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCandidateConsolidator.kt`: proposal clustering, cross-class precedence, bubble association, and reading order.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCropPolicy.kt`: the three deterministic crop descriptors.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrQualityEvaluator.kt`: normalization, repetition checks, agreement, and terminal-quality decisions.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrJobReducer.kt`: legal job/page/region transitions and interruption recovery.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrArtifactStore.kt`: region checkpoints, page commit, strict reads, and atomic run publication.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/serialization/OcrJson.kt`: strict JSON codec.
- Create `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt`: shared complete OCR contract fixtures used by core tests.

### Model package and Android runtime

- Create `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackage.kt`: pinned two-file PaddleOCR package metadata and install state.
- Create `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackageStore.kt`: resumable, length- and capability-validated two-file publication.
- Create `app/src/main/java/rs/masumi/app/ocr/OcrEngine.kt`: engine/factory/result boundary.
- Create `app/src/main/java/rs/masumi/app/ocr/OcrModelProvider.kt`: HTTP Range source and model acquisition.
- Create `app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt`: JNI lifecycle adapter and sanitized exception mapping.
- Create `app/src/main/cpp/CMakeLists.txt`: pinned native targets.
- Create `app/src/main/cpp/paddle_ocr_jni.cpp`: llama.cpp/mtmd bridge.
- Add `.gitmodules` and gitlink `third_party/llama.cpp`: exact upstream commit behind tag `b8935`.

### Android orchestration and UI

- Create `app/src/main/java/rs/masumi/app/ocr/OcrCropRenderer.kt`: visible-page crops from portable descriptors.
- Create `app/src/main/java/rs/masumi/app/ocr/OcrPreviewRenderer.kt`: terminal-state overlays.
- Create `app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt`: model/session lifecycle, region retries, checkpoints, report, and publication.
- Create `app/src/main/java/rs/masumi/app/ocr/OcrForegroundService.kt`: foreground execution and recovery.
- Create `app/src/main/java/rs/masumi/app/ocr/OcrStatusBroadcast.kt`: package-scoped progress.
- Modify `app/src/main/java/rs/masumi/app/detection/ProjectCatalog.kt`: expose latest valid detection dependency and OCR runs.
- Modify `app/src/main/java/rs/masumi/app/MainActivity.kt`: start/cancel OCR, preview navigation, and recognized-text details.
- Modify `app/src/main/res/layout/activity_main.xml`, `app/src/main/res/values/strings.xml`, and `app/src/main/AndroidManifest.xml`: OCR controls, text, permission, and service.
- Modify `app/build.gradle.kts`, `README.md`, and `docs/architecture/foundation.md`: native build and architecture documentation.

---

### Task 1: Define strict OCR contracts and JSON

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/serialization/OcrJson.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJsonTest.kt`

- [ ] **Step 1: Write the failing strict-round-trip test**

Create a representative `PageOcrArtifact` containing one `RECOGNIZED` region and assert encode/decode equality. Also append `,"unexpected":true` before the final object brace and assert `SerializationException`:

```kotlin
@Test
fun `OCR JSON is strict and round trips terminal region data`() {
    val artifact = OcrFixtures.pageArtifact(
        state = OcrRegionState.RECOGNIZED,
        rawText = "縦書きです",
        normalizedText = "縦書きです",
    )
    val encoded = OcrJson().encodePageArtifact(artifact)
    assertEquals(artifact, OcrJson().decodePageArtifact(encoded))
    assertFailsWith<SerializationException> {
        OcrJson().decodePageArtifact(encoded.dropLast(2) + ",\"unexpected\":true\n}")
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrJsonTest`

Expected: compilation fails because `PageOcrArtifact`, `OcrRegionState`, and `OcrJson` do not exist.

- [ ] **Step 3: Add the minimal complete contract family**

Define `OCR_SCHEMA_VERSION = 1`; model file/package/runtime refs; consolidation, crop, generation, and quality configs; `OcrCandidate`; `OcrAttemptArtifact`; `OcrRegionArtifact`; `PageOcrArtifact`; `OcrJobRecord`; job pages and region checkpoints; `OcrRunArtifact`; `OcrReport`; and these enums:

```kotlin
@Serializable enum class OcrSemanticStatus { REQUIRED_TEXT, UNRESOLVED_FREE_TEXT }
@Serializable enum class OcrProtectionPolicy { NONE, PRESERVE_UNTIL_CLASSIFIED }
@Serializable enum class OcrRegionState {
    PENDING, RUNNING, RECOGNIZED, NEEDS_FALLBACK, NO_TEXT_CONFIRMED, PRESERVED_SOURCE,
}
@Serializable enum class OcrPageState { PENDING, RUNNING, COMMITTED, PRESERVED_SOURCE }
@Serializable enum class OcrJobStatus {
    QUEUED, DOWNLOADING_MODEL, LOADING_MODEL, RUNNING,
    SUCCEEDED, SUCCEEDED_WITH_PRESERVED_REGIONS, CANCELLED, FAILED,
}
@Serializable enum class OcrCropStrategy { PADDED_TEXT, TIGHT_TEXT, CONTEXT_TEXT }
```

`OcrAttemptArtifact` must contain crop strategy/box, raw/normalized text, token IDs/probabilities, dimensions, token counts, stop flags, timings, and sanitized error. `OcrRegionArtifact` must contain candidate provenance, bubble association, reading rank, attempts, selected attempt, quality record, terminal state, and error. Use `PixelBox` and `VisibleOrientation` from the detection contract rather than duplicating geometry types.

Add `internal object OcrFixtures` with named-argument factories for a complete model package, dependencies, candidate, attempt, terminal region, page artifact, and job. Tests may override only the field under test while every serialized record remains structurally valid.

Implement `OcrJson` with the same `Json` settings as `DetectionJson`: `prettyPrint`, defaults, explicit nulls, and `ignoreUnknownKeys = false`.

- [ ] **Step 4: Run the focused and full core tests**

Run: `./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrJsonTest && ./gradlew :pipeline-core:test`

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt \
  pipeline-core/src/main/kotlin/rs/masumi/core/serialization/OcrJson.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJsonTest.kt
git commit -m "feat: define strict OCR artifacts"
```

### Task 2: Consolidate candidates and compute reading order

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCandidateConsolidator.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrCandidateConsolidatorTest.kt`

- [ ] **Step 1: Write failing behavior tests**

Cover same-class IoU `0.75`, smaller-box containment `0.90`, cross-class smaller-box coverage `0.70`, text-to-bubble coverage `0.50`, no proximity-only merge, and right-to-left order inside a vertical-overlap band:

Define private `page(...)` and `region(...)` factories in this test file so each case constructs a complete `PageDetectionArtifact` and `DetectedRegion` without relying on hidden global fixtures.

```kotlin
@Test
fun `cross-class duplicate keeps in-bubble semantics and all provenance`() {
    val free = region("a", DetectorClass.TEXT_FREE, PixelBox(10.0, 10.0, 50.0, 90.0), 0.90)
    val inside = region("b", DetectorClass.TEXT_IN_BUBBLE, PixelBox(12.0, 12.0, 48.0, 88.0), 0.80)
    val result = OcrCandidateConsolidator().consolidate(page(), emptyList(), listOf(free, inside))
    assertEquals(1, result.size)
    assertEquals(listOf("a", "b"), result.single().sourceRegionIds)
    assertEquals(OcrSemanticStatus.REQUIRED_TEXT, result.single().semanticStatus)
}

@Test
fun `nearby non-overlapping text is never merged`() {
    val result = OcrCandidateConsolidator().consolidate(
        page(),
        emptyList(),
        listOf(
            region("a", DetectorClass.TEXT_IN_BUBBLE, PixelBox(10.0, 10.0, 40.0, 60.0), 0.9),
            region("b", DetectorClass.TEXT_IN_BUBBLE, PixelBox(42.0, 10.0, 72.0, 60.0), 0.9),
        ),
    )
    assertEquals(2, result.size)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrCandidateConsolidatorTest`

Expected: compilation fails because the consolidator is missing.

- [ ] **Step 3: Implement deterministic geometry and ordering**

Implement pure helpers for area, intersection, IoU, smaller-box coverage, clipped union, cluster selection, and bubble association. Sort source IDs before identity use. Build horizontal bands when vertical intersection covers `0.35` of the shorter region; order bands by minimum top and members by right descending, top ascending, region ID ascending. Never depend on unordered-map iteration order.

The public API must remain:

```kotlin
class OcrCandidateConsolidator(
    private val config: OcrConsolidationConfig = OcrConsolidationConfig(),
) {
    fun consolidate(
        detectionPage: PageDetectionArtifact,
        bubbles: List<DetectedRegion> = detectionPage.bubbleCandidates,
        textRegions: List<DetectedRegion> = detectionPage.textRegions,
    ): List<OcrCandidate>
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrCandidateConsolidatorTest && ./gradlew :pipeline-core:test`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCandidateConsolidator.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrCandidateConsolidatorTest.kt
git commit -m "feat: consolidate OCR candidates"
```

### Task 3: Add crop policy, normalization, quality, and artifact identity

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCropPolicy.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrQualityEvaluator.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrIdentity.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrCropPolicyTest.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrQualityEvaluatorTest.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrIdentityTest.kt`

- [ ] **Step 1: Write failing crop and quality tests**

Assert `12%`, `4%`, and `24%` crop expansion, four-pixel minimum padding, page clipping, and bubble clipping for `CONTEXT_TEXT`. Assert NFC and code-fence normalization, repetition/truncation rejection, two empty attempts becoming `NO_TEXT_CONFIRMED`, agreement at `0.90`, and primary geometric-mean probability at `0.55`:

Define a private `attempt(...)` factory in `OcrQualityEvaluatorTest` that supplies complete token, stop, size, and timing fields while allowing text, probability, and truncation overrides.

```kotlin
@Test
fun `two agreeing valid attempts are recognized`() {
    val decision = OcrQualityEvaluator().evaluate(
        detectorConfidence = 0.4,
        attempts = listOf(attempt("今日は", 0.50), attempt("今日は", 0.48)),
    )
    assertEquals(OcrRegionState.RECOGNIZED, decision.state)
    assertEquals(0, decision.selectedAttemptIndex)
}

@Test
fun `forced truncation is never recognized`() {
    val decision = OcrQualityEvaluator().evaluate(
        detectorConfidence = 0.9,
        attempts = listOf(attempt("長い文", 0.99, truncated = true)),
    )
    assertEquals(OcrRegionState.NEEDS_FALLBACK, decision.state)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :pipeline-core:test --tests 'rs.masumi.core.ocr.Ocr*Test'`

Expected: the three production classes are missing.

- [ ] **Step 3: Implement the three pure components**

`OcrCropPolicy.attempts(candidate, pageWidth, pageHeight, bubbleBox)` returns three `OcrCropDescriptor`s in fixed order. `OcrQualityEvaluator.normalize` performs CRLF-to-LF, one surrounding code-fence removal, trim, and NFC. Its evaluator uses normalized Levenshtein similarity and the exact terminal rules in the design.

`OcrIdentity` allocates safe opaque run/page IDs and derives child region IDs structurally from persisted lineage:

```kotlin
fun regionId(pageId: String, detectionPageArtifactKey: String, sourceRegionIds: List<String>, semantic: OcrSemanticStatus): String
fun newPageArtifactKey(idSource: IdSource): String
fun newRunArtifactKey(idSource: IdSource): String
```

Persist every config/model/runtime/prompt dependency beside the page key and compare those fields explicitly for reuse; exclude output, timing, filename, and timestamp fields.

- [ ] **Step 4: Run tests**

Run: `./gradlew :pipeline-core:test --tests 'rs.masumi.core.ocr.Ocr*Test' && ./gradlew :pipeline-core:test`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCropPolicy.kt \
  pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrQualityEvaluator.kt \
  pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrIdentity.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr
git commit -m "feat: evaluate OCR attempts"
```

### Task 4: Persist region-level resumable OCR jobs

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrJobReducer.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrArtifactStore.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJobReducerTest.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrArtifactStoreTest.kt`

- [ ] **Step 1: Write failing reducer and store tests**

Cover the complete job transition graph, terminal region commit before next start, interrupted `RUNNING` region returning to `PENDING`, cancellation retaining terminal checkpoints, invalid checkpoint rejection, duplicate page reuse, and final atomic publication:

```kotlin
@Test
fun `interruption repeats only the running region`() {
    val running = jobWithRegions(
        OcrRegionState.RECOGNIZED,
        OcrRegionState.RUNNING,
        OcrRegionState.PENDING,
    )
    val recovered = OcrJobReducer.recoverInterrupted(running, 200L)
    assertEquals(
        listOf(OcrRegionState.RECOGNIZED, OcrRegionState.PENDING, OcrRegionState.PENDING),
        recovered.pages.single().regions.map { it.state },
    )
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :pipeline-core:test --tests 'rs.masumi.core.ocr.Ocr*StoreTest' --tests 'rs.masumi.core.ocr.OcrJobReducerTest'`

Expected: reducer and store classes are missing.

- [ ] **Step 3: Implement reducer and region checkpoint store**

Use `staging/ocr/<job>/<run>/pages/<page>/regions/<ocr-region-id>.json` for terminal region checkpoints. Write to a unique part file and atomically move it before journaling the terminal state. Commit `pages/<page>/ocr.json` and ordered previews only after every candidate is terminal. Publish `artifact.json` and `report.json`, then atomically move the whole run directory to `artifacts/ocr/<run>`.

The reducer must expose explicit methods for download/load/run, start region, commit terminal region, commit page, request/finish cancellation, recover interruption, resume cancellation, finish success, and fail job. A successful job uses `SUCCEEDED_WITH_PRESERVED_REGIONS` when any region is `NEEDS_FALLBACK` or `PRESERVED_SOURCE`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :pipeline-core:test --tests 'rs.masumi.core.ocr.*' && ./gradlew :pipeline-core:test`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrJobReducer.kt \
  pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrArtifactStore.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJobReducerTest.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrArtifactStoreTest.kt
git commit -m "feat: checkpoint OCR regions"
```

### Task 5: Install the pinned two-file model package resumably

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackage.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackageStore.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/modelpackage/OcrModelPackageStoreTest.kt`
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrModelProvider.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/ocr/OcrModelProviderTest.kt`

- [ ] **Step 1: Write failing package-store tests**

Use generated byte arrays and fake ranged responses. Cover resume from a valid partial, restart on `200`, rejection on bad length, interrupted-normalization restart, both-file capability validation, and no publication after the first file only.

```kotlin
@Test
fun `valid range response resumes and publishes both verified files`() {
    val source = FakeRangeSource(files, splitAt = 5)
    val installed = store.ensureInstalled("install", descriptor, source, validator) { _, _ -> }
    assertContentEquals(files.getValue(descriptor.model.fileName), installed.model.readBytes())
    assertContentEquals(files.getValue(descriptor.projector.fileName), installed.projector.readBytes())
    assertNotNull(store.readInstalled(descriptor))
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :pipeline-core:test --tests rs.masumi.core.modelpackage.OcrModelPackageStoreTest`

Expected: OCR package types do not exist.

- [ ] **Step 3: Implement package descriptor and core store**

Pin revision `511b09642bb324401f15f97cc23bc67e8f0a291d`, model filename/length `PaddleOCR-VL-1.6-GGUF.gguf`/`935769056`, projector filename/length `PaddleOCR-VL-1.6-GGUF-mmproj.gguf`/`881770560`, storage revision `paddleocr-vl-1.6-f16-v1`, Apache-2.0, prompt `OCR:`, llama tag `b8935`, and runtime `arm64-v8a:cpu:mtmd`. The store must never treat one installed file or a `*.part` as a complete package, and must validate the required native capabilities before publication.

- [ ] **Step 4: Implement HTTP Range adapter and instrumentation test**

Use `HttpURLConnection`, set `Range: bytes=<offset>-`, require `Content-Range` for resume, disconnect on close, and expose only status, total length, and stream through `OcrRangeResponse`. Test with a local fake source rather than the public network.

- [ ] **Step 5: Run host and Android tests**

Run: `./gradlew :pipeline-core:test :app:compileDebugAndroidTestKotlin`

Expected: all pass/compile.

- [ ] **Step 6: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackage.kt \
  pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackageStore.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/modelpackage/OcrModelPackageStoreTest.kt \
  app/src/main/java/rs/masumi/app/ocr/OcrModelProvider.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/OcrModelProviderTest.kt
git commit -m "feat: install PaddleOCR model package"
```

### Task 6: Build a pinned JNI/llama.cpp OCR runtime

**Files:**
- Create: `.gitmodules`
- Create gitlink: `third_party/llama.cpp`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/paddle_ocr_jni.cpp`
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrEngine.kt`
- Create: `app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt`

- [ ] **Step 1: Add the pinned submodule and failing JNI contract test**

Run:

```bash
git submodule add https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
git -C third_party/llama.cpp checkout f454bd7eb8944629aabca163ea1c6e67e53fd77e
```

Write a test asserting invalid paths and malformed RGB length return stable `OcrEngineException` codes without native path text.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: engine and JNI adapter types are missing.

- [ ] **Step 3: Add external native build and minimal lifecycle JNI**

Configure `externalNativeBuild.cmake`, C++17, `abiFilters += "arm64-v8a"`, and a pinned NDK version. In CMake disable llama examples/tests/server/curl/OpenMP/native CPU dispatch, add llama.cpp as a subdirectory, create a static `masumi_mtmd` from the source list in `tools/mtmd/CMakeLists.txt`, and link it with `llama`, `ggml`, `android`, and `log` into `masumi_ocr`.

Expose JNI functions for `nativeCreate`, `nativeRecognize`, `nativeCancel`, and `nativeDestroy`. The minimal first implementation validates arguments, owns an opaque handle, and returns sanitized error codes; it does not fabricate OCR text.

Define the Kotlin boundary:

```kotlin
interface OcrEngine : AutoCloseable {
    fun recognize(request: OcrEngineRequest, cancellation: () -> Boolean): OcrEngineResult
    fun cancel()
}

fun interface OcrEngineFactory {
    fun open(model: Path, projector: Path): OcrEngine
}
```

- [ ] **Step 4: Build and run the contract test**

Run: `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin`

Expected: native library and Android test APK build successfully.

- [ ] **Step 5: Commit the native skeleton**

```bash
git add .gitmodules third_party/llama.cpp app/build.gradle.kts app/src/main/cpp \
  app/src/main/java/rs/masumi/app/ocr/OcrEngine.kt \
  app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt
git commit -m "feat: add native OCR runtime"
```

- [ ] **Step 6: Write a failing connected smoke test for real inference**

Gate the test with model file arguments supplied through instrumentation. Generate a small high-contrast Japanese crop at runtime, call `recognize`, and assert non-empty UTF-8, at least one token/probability pair, matching vector lengths, and no truncation.

- [ ] **Step 7: Implement PaddleOCR-VL inference in C++**

Initialize llama backend; load the GGUF model; read the embedded chat template; initialize `mtmd_context` from the projector with the PaddleOCR image marker; verify vision support; create an RGB `mtmd_bitmap`; render the single user message containing image plus `OCR:`; tokenize/evaluate chunks; create a crop-specific context; greedily generate up to 256 tokens with repetition penalty 1.2; capture the selected-token probability from logits before acceptance; append UTF-8 safely; stop at EOS, cancellation, truncation, or repeated unit; and free crop-specific objects on every exit path.

Return a compact JSON payload matching `OcrEngineResult`; Kotlin parses it with strict kotlinx.serialization. Native exceptions cross JNI only as stable codes such as `MODEL_LOAD`, `PROJECTOR_LOAD`, `VISION_UNSUPPORTED`, `IMAGE_INVALID`, `TOKENIZE`, `CONTEXT`, `DECODE`, `UTF8`, and `CANCELLED`.

- [ ] **Step 8: Build and run synthetic/native tests**

Run: `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin`

Then, when model arguments are available: `adb shell am instrument -w -e class rs.masumi.app.ocr.NativePaddleOcrSmokeTest rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner`

Expected: build passes; connected smoke returns recognized UTF-8 without a native crash.

- [ ] **Step 9: Commit full inference**

```bash
git add app/src/main/cpp/paddle_ocr_jni.cpp \
  app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrSmokeTest.kt
git commit -m "feat: run PaddleOCR-VL on Android"
```

### Task 7: Render crops and OCR previews

**Files:**
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrCropRenderer.kt`
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrPreviewRenderer.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/ocr/OcrCropRendererTest.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/ocr/OcrPreviewRendererTest.kt`

- [ ] **Step 1: Write failing generated-image tests**

Create a checkerboard source and assert exact crop pixels/dimensions for page-edge clipping and each strategy. Assert preview dimensions equal source dimensions and exact green/amber/gray/red/orange colors are present for each state/protection combination.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: renderers are missing.

- [ ] **Step 3: Implement bitmap rendering**

`OcrCropRenderer.render(page, descriptor)` uses the already oriented visible bitmap, integer floor/ceil clipping, and PNG or lossless RGB transport accepted by `OcrEngineRequest`. It never recycles the caller bitmap. `OcrPreviewRenderer` copies the page, draws state outlines and short labels, and returns PNG bytes without writing the source.

- [ ] **Step 4: Run connected renderer tests**

Run: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && adb shell am instrument -w -e class rs.masumi.app.ocr.OcrCropRendererTest,rs.masumi.app.ocr.OcrPreviewRendererTest rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner`

Expected: tests pass and installation replaces the existing debug app.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rs/masumi/app/ocr/OcrCropRenderer.kt \
  app/src/main/java/rs/masumi/app/ocr/OcrPreviewRenderer.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/OcrCropRendererTest.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/OcrPreviewRendererTest.kt
git commit -m "feat: render OCR crops and previews"
```

### Task 8: Orchestrate resumable region OCR

**Files:**
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt`
- Modify: `app/src/main/java/rs/masumi/app/detection/ProjectCatalog.kt`

- [ ] **Step 1: Write failing fake-engine integration tests**

Cover candidate consolidation, three-attempt selection, region checkpoint before next inference, duplicate-source reuse, one region failure continuing, cancellation during token generation, source/detection immutability, and restart skipping terminal regions.

```kotlin
@Test
fun `restart reuses committed regions and repeats only interrupted region`() {
    val first = FakeOcrEngine(crashOnCall = 2)
    assertFails { runner(first).run(projectId, { false }) {} }
    val second = FakeOcrEngine()
    val result = runner(second).run(projectId, { false }) {}
    assertEquals(remainingRegionCount, second.requests.size)
    assertTrue(result.job.status.isSuccessful())
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: `OcrRunner` is missing.

- [ ] **Step 3: Implement the runner**

Open the latest valid detection run from `ProjectCatalog`; validate source and detection dependencies; derive page/run keys; reuse a published OCR run or recover a compatible job; acquire and load the model once; process unique pages and regions sequentially; persist `RUNNING` before inference; render attempts lazily; evaluate after each attempt; commit terminal regions atomically; commit page JSON/preview; finish report; and atomically publish the run.

Progress must include job/run IDs, status, current page/order/region, terminal region counts, page counts, two-file download bytes, and safe error code. Cancellation calls `engine.cancel()` and returns the active region to `PENDING` before terminal job cancellation.

- [ ] **Step 4: Run runner and regression tests**

Run: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && adb shell am instrument -w -e class rs.masumi.app.ocr.OcrRunnerTest rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner && ./gradlew test`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt \
  app/src/main/java/rs/masumi/app/detection/ProjectCatalog.kt
git commit -m "feat: orchestrate resumable OCR"
```

### Task 9: Add foreground service, broadcasts, restart recovery, and UI

**Files:**
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrForegroundService.kt`
- Create: `app/src/main/java/rs/masumi/app/ocr/OcrStatusBroadcast.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/ocr/OcrStatusBroadcastTest.kt`
- Modify: `app/src/main/java/rs/masumi/app/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/androidTest/java/rs/masumi/app/MainActivityLayoutTest.kt`

- [ ] **Step 1: Write failing broadcast/layout/recovery tests**

Assert package-scoped broadcast validation; OCR button disabled without a completed detection run; start/cancel/progress states; detail text in reading order; preview navigation; and startup resumes a non-terminal OCR job without creating a second concurrent service task.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: OCR service/status/UI symbols are missing.

- [ ] **Step 3: Implement service and status boundary**

Mirror the proven detection service pattern with a dedicated single-thread executor, `START_REDELIVER_INTENT`, package permission, foreground notification, cancellation action, and one active project. `onDestroy` sets cancellation and calls `engine.cancel` through the runner lifecycle. Notifications distinguish downloading, loading, running, success-with-preserved, cancellation, and failure. `MainActivity.onCreate` checks the persisted job once and redelivers only a non-terminal job when the service reports no active task.

- [ ] **Step 4: Implement minimal OCR UI**

Add `Run OCR`, `Cancel OCR`, model/download progress, current region status, OCR preview mode, recognized source-text detail, and previous/next controls. Keep import and detection behavior unchanged. A terminal low-confidence region is displayed but requires no user confirmation.

- [ ] **Step 5: Run instrumentation and launch smoke**

Run: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk && adb shell am instrument -w rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner && adb shell am force-stop rs.masumi.app.dev && adb shell monkey -p rs.masumi.app.dev 1`

Expected: all instrumentation passes and app launches without crash.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/rs/masumi/app/ocr app/src/main/java/rs/masumi/app/MainActivity.kt \
  app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml \
  app/src/main/AndroidManifest.xml app/src/androidTest
git commit -m "feat: expose background OCR workflow"
```

### Task 10: Verify real model, recovery, performance, and public docs

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/foundation.md`
- Modify: `docs/superpowers/specs/2026-07-13-paddleocr-vl-ocr-design.md` only if verified implementation behavior differs from the approved contract.
- Private only, never stage: real model files, source pages, OCR outputs, timings, screenshots, and comparison notes.

- [ ] **Step 1: Run clean automated verification**

Run:

```bash
./gradlew clean :pipeline-core:test :app:assembleDebug :app:lint :app:assembleDebugAndroidTest
git diff --check
```

Expected: every command exits zero and lint has no new errors.

- [ ] **Step 2: Replace-install and run full instrumentation**

Run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: all tests pass without uninstalling the debug app.

- [ ] **Step 3: Acquire the pinned model and run representative chapter OCR**

Use the app's model installer, verify both on-device filenames, lengths, metadata, and native capabilities, run a representative imported chapter, and require every eligible region to reach a terminal state. Keep the terminal report and measurements outside the repository.

- [ ] **Step 4: Verify interruption and cancellation on real inference**

Force-stop during generated-token work, relaunch, and confirm only the interrupted region is repeated. Cancel during another region, confirm generation stops, then resume and confirm prior terminal checkpoints are reused.

- [ ] **Step 5: Compare Android and desktop PaddleOCR-VL privately**

Run the exact pinned model against matching crops in the reference desktop runtime. Compare normalized text, region coverage, and character similarity; inspect low-confidence/preserved cases without adding private data to tests or docs. Confirm steady-state page latency is within 180 seconds and no native OOM occurs.

- [ ] **Step 6: Update public docs truthfully**

Document that OCR is implemented, model download size is approximately 1.8 GB, local arm64 inference is sequential, uncertainty is protected, and translation/cleanup/typesetting/export remain deferred. Do not include corpus, device, account, path, screenshot, timing sample, or private output details.

- [ ] **Step 7: Re-run verification and inspect repository privacy**

Run:

```bash
./gradlew :pipeline-core:test :app:assembleDebug :app:lint
rg -n 'absolute user path|device-specific identifier|corpus identifier|api[_-]?key|Bearer ' . \
  -g '!build/**' -g '!.git/**' || true
git status --short
git diff --check
```

Expected: tests/build/lint pass; search shows no private data or credentials; status contains only intended source/docs/submodule changes.

- [ ] **Step 8: Commit final verified behavior**

```bash
git add README.md docs app pipeline-core .gitmodules third_party/llama.cpp
git commit -m "feat: finish local PaddleOCR-VL OCR"
```

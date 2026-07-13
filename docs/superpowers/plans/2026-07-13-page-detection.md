# Masumi Page Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Analyze every immutable page in an imported project with a pinned local ONNX comic detector and publish resumable region JSON, annotated previews, and a terminal report without modifying source images.

**Architecture:** Keep schemas, deterministic IDs, post-processing, state transitions, model-package integrity, and atomic artifact publication in `pipeline-core`. Keep bitmap decoding, EXIF handling, tensor creation, ONNX Runtime, foreground execution, notifications, and preview UI in `app`; Android orchestration depends on detector and model-provider interfaces so tests use generated images and fake inference.

**Tech Stack:** Kotlin 2.3.0, JDK 17, Android SDK 36/minSdk 26, kotlinx.serialization JSON 1.9.0, ONNX Runtime Android 1.27.0, platform `ExifInterface`, `HttpURLConnection`, JUnit 4, Android instrumentation tests.

---

## File map

Portable core:

- `core/detection/DetectionContracts.kt`: serializable model, query, region, job, run, and report records.
- `core/detection/DetectionIdentity.kt`: canonical SHA-256 page keys, run keys, and region IDs.
- `core/detection/DetectionPostProcessor.kt`: output validation, clipping, thresholding, and class separation.
- `core/detection/DetectionJobReducer.kt`: legal job/page transitions, retry, cancellation, recovery, and terminal status.
- `core/detection/DetectionArtifactStore.kt`: journals, job-owned checkpoints, page commits, and atomic run publication.
- `core/modelpackage/DetectorModelPackage.kt` and `DetectorModelPackageStore.kt`: pinned metadata, streamed integrity checks, signature callback, and atomic publication.
- `core/serialization/DetectionJson.kt`: strict detection JSON codecs.

Android application:

- `app/detection/PageBitmapDecoder.kt`: source decode and in-memory EXIF orientation.
- `app/detection/OnnxInputPreprocessor.kt`: exact `640 x 640` RGB CHW tensor preparation.
- `app/detection/ComicDetector.kt` and `OnnxComicDetector.kt`: runtime-neutral boundary plus ONNX adapter.
- `app/detection/DetectorModelProvider.kt`: pinned HTTPS acquisition into the core model store.
- `app/detection/DetectionPreviewRenderer.kt`: blue/green/orange derived PNG overlays.
- `app/detection/ProjectCatalog.kt` and `DetectionRunner.kt`: project discovery, sequential inference, retry, checkpointing, and reporting.
- `app/detection/DetectionForegroundService.kt`: worker ownership, notification, cancellation, and restart delivery.
- `MainActivity.kt` and `activity_main.xml`: analysis progress and preview navigation.

## Task 1: Define strict detection contracts and JSON

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/detection/DetectionContracts.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/serialization/DetectionJson.kt`
- Test: `pipeline-core/src/test/kotlin/rs/masumi/core/detection/DetectionJsonTest.kt`

- [x] **Step 1: Write failing round-trip tests**

Construct a `PageDetectionArtifact` with one raw query, one bubble, one in-bubble text region, and one protected free-text region. Assert encode/decode equality. Inject an unknown JSON field and assert strict decoding fails.

```kotlin
@Test
fun `free text protection survives json round trip`() {
    val artifact = fixturePageArtifact(
        textRegions = listOf(
            DetectedRegion(
                regionId = "region-free",
                queryIndex = 2,
                detectorClass = DetectorClass.TEXT_FREE,
                confidence = 0.75,
                box = PixelBox(1.0, 2.0, 30.0, 40.0),
                semanticStatus = RegionSemanticStatus.UNRESOLVED_FREE_TEXT,
                protectionPolicy = RegionProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED,
            ),
        ),
    )
    val codec = DetectionJson()
    assertEquals(artifact, codec.decodePageArtifact(codec.encodePageArtifact(artifact)))
}
```

- [x] **Step 2: Run RED**

Run: `./gradlew :pipeline-core:test --tests '*DetectionJsonTest'`

Expected: compilation fails because detection contracts and `DetectionJson` do not exist.

- [x] **Step 3: Implement contracts and strict codecs**

Define schema-version-1 serializable `DetectorClass`, `RawQueryValidation`, `RegionSemanticStatus`, `RegionProtectionPolicy`, `VisibleOrientation`, `PixelBox`, `DetectorModelRef`, `DetectionPreprocessingConfig`, `DetectionThresholdConfig`, `RawQueryRecord`, `DetectedRegion`, `PageDetectionArtifact`, `DetectionPageState`, `DetectionJobStatus`, `DetectionError`, `DetectionJobPage`, `DetectionJobRecord`, `DetectionRunEntry`, `DetectionRunArtifact`, and `DetectionReport`. Paths are project-relative; errors are stable code plus safe message.

Use one private `Json` with `prettyPrint`, `encodeDefaults`, and `explicitNulls` enabled and `ignoreUnknownKeys = false`. Add named encode/decode pairs for page artifacts, jobs, runs, and reports.

- [x] **Step 4: Run GREEN and commit**

```bash
./gradlew :pipeline-core:test --tests '*DetectionJsonTest'
git add pipeline-core/src
git commit -m "feat: define detection artifact contracts"
```

Expected: round-trip and unknown-field tests pass.

## Task 2: Generate deterministic identities and accepted regions

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/detection/DetectionIdentity.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/detection/DetectionPostProcessor.kt`
- Test: `pipeline-core/src/test/kotlin/rs/masumi/core/detection/DetectionIdentityTest.kt`
- Test: `pipeline-core/src/test/kotlin/rs/masumi/core/detection/DetectionPostProcessorTest.kt`

- [x] **Step 1: Write failing tests**

Assert page keys change for source SHA, schema, model revision/SHA, runtime revision, preprocessing, or thresholds. Region IDs change for class/query but not geometry/confidence. Run keys depend on ordered page keys and reject duplicate/non-contiguous orders.

Create exactly 300 model queries. Cover reversed/clipped coordinates, below-threshold score, unknown label, non-finite score/box, and zero-area boxes. Assert all raw records remain, bubble/text lists are separate, and `TEXT_FREE` is protected.

- [x] **Step 2: Run RED**

```bash
./gradlew :pipeline-core:test --tests '*DetectionIdentityTest' --tests '*DetectionPostProcessorTest'
```

Expected: compilation fails because identity and post-processing types do not exist.

- [x] **Step 3: Implement canonical hashing and processing**

Expose:

```kotlin
object DetectionIdentity {
    fun pageArtifactKey(
        sourceSha256: String,
        schemaVersion: Int,
        model: DetectorModelRef,
        preprocessing: DetectionPreprocessingConfig,
        thresholds: DetectionThresholdConfig,
    ): String
    fun runArtifactKey(entries: List<Pair<Int, String>>): String
    fun regionId(
        pageId: String,
        pageArtifactKey: String,
        queryIndex: Int,
        detectorClass: DetectorClass,
    ): String
}

data class ModelQuery(
    val queryIndex: Int,
    val label: Long,
    val score: Float,
    val box: FloatArray,
)
```

Hash fixed-order UTF-8 fields and length-prefix strings. Require lowercase 64-character SHA values and contiguous indexes. Post-processing requires queries `0..299`, validates score before threshold, validates four coordinates, normalizes and clips boxes, maps labels `0/1/2` only, and never merges bubbles with text.

- [x] **Step 4: Run GREEN and commit**

```bash
./gradlew :pipeline-core:test --tests '*DetectionIdentityTest' --tests '*DetectionPostProcessorTest'
git add pipeline-core/src
git commit -m "feat: derive stable detection regions"
```

## Task 3: Persist resumable jobs and atomically publish artifacts

**Files:**
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/io/ProjectFileSystem.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/detection/DetectionJobReducer.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/detection/DetectionArtifactStore.kt`
- Test: `pipeline-core/src/test/kotlin/rs/masumi/core/detection/DetectionJobReducerTest.kt`
- Test: `pipeline-core/src/test/kotlin/rs/masumi/core/detection/DetectionArtifactStoreTest.kt`

- [x] **Step 1: Write failing reducer and store tests**

Cover `PENDING -> RUNNING -> COMMITTED`, first failure back to `PENDING`, second failure to `PRESERVED_SOURCE`, interrupted recovery, cancellation, terminal success, success with preserved pages, and illegal transitions.

Assert job JSON atomic replacement, job-owned checkpoint paths, no final artifact before all pages terminate, committed checkpoint survival, interrupted-page cleanup, atomic final publication, foreign staging preservation, and referenced-file validation before cache reuse.

- [x] **Step 2: Run RED**

```bash
./gradlew :pipeline-core:test --tests '*DetectionJobReducerTest' --tests '*DetectionArtifactStoreTest'
```

- [x] **Step 3: Implement reducer, filesystem primitives, and store**

Add `readUtf8`, `replaceUtf8`, and `replaceFile` to `ProjectFileSystem`. Atomic replacement writes a sibling `.new`, flushes, then moves with `ATOMIC_MOVE` and `REPLACE_EXISTING`.

Reducer operations are `startModelDownload`, `startRunning`, `startPage`, `recordRetry`, `commitPage`, `preservePage`, `requestCancel`, `recoverInterrupted`, `finish`, and `failJob`; each receives the new epoch time.

Store operations are:

```kotlin
fun writeJob(job: DetectionJobRecord)
fun readJob(jobId: String): DetectionJobRecord?
fun findResumableJob(): DetectionJobRecord?
fun cleanInterruptedPage(job: DetectionJobRecord, page: DetectionJobPage)
fun commitPage(
    job: DetectionJobRecord,
    pageArtifact: PageDetectionArtifact,
    previewPng: ByteArray,
    orders: List<Int>,
)
fun validateCommittedPage(job: DetectionJobRecord, page: DetectionJobPage): Boolean
fun publishRun(
    job: DetectionJobRecord,
    artifact: DetectionRunArtifact,
    report: DetectionReport,
): Path
```

Validate IDs/relative paths before resolution. Checkpoint under `staging/detection/<job-id>/<run-key>` and atomically move the complete run to `artifacts/detection/<run-key>`.

- [x] **Step 4: Run GREEN and commit**

```bash
./gradlew :pipeline-core:test --tests '*DetectionJobReducerTest' --tests '*DetectionArtifactStoreTest'
git add pipeline-core/src
git commit -m "feat: persist resumable detection jobs"
```

## Task 4: Install and validate the pinned model package

**Files:**
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/DetectorModelPackage.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/DetectorModelPackageStore.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/serialization/DetectionJson.kt`
- Test: `pipeline-core/src/test/kotlin/rs/masumi/core/modelpackage/DetectorModelPackageStoreTest.kt`

- [x] **Step 1: Write failing integrity tests**

Use generated bytes plus a fake signature validator. Exact length/SHA publishes `model.onnx` and metadata. Wrong length, SHA, or signature publishes nothing. Partial files are cleaned. Valid existing packages do not reopen the supplied stream. Distinct install IDs cannot delete each other's staging.

- [x] **Step 2: Define the pinned descriptor and implement the store**

Use repository `ogkalu/comic-text-and-bubble-detector`, revision `16e8a622f91fabc6b5b65c96d32d1183f8843546`, file `detector-v4-s_int8.onnx`, byte length `11120765`, SHA-256 `5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79`, Apache-2.0, opset 18, and runtime revision `onnxruntime-android:1.27.0`.

Stream to `models/.staging/<install-id>/model.onnx.part`, count/hash, call `ModelSignatureValidator`, write safe metadata, rename to `model.onnx`, and atomically publish under `models/<storage-key>/<sha256>`. Revalidate existing packages before reuse.

Add model-package metadata encode/decode methods to `DetectionJson` in the same task, after `DetectorModelPackageMetadata` exists.

- [x] **Step 3: Run GREEN and commit**

```bash
./gradlew :pipeline-core:test --tests '*DetectorModelPackageStoreTest'
git add pipeline-core/src
git commit -m "feat: install pinned detector package"
```

## Task 5: Decode pages, build tensors, and render previews

**Files:**
- Create: `app/src/main/java/rs/masumi/app/detection/PageBitmapDecoder.kt`
- Create: `app/src/main/java/rs/masumi/app/detection/OnnxInputPreprocessor.kt`
- Create: `app/src/main/java/rs/masumi/app/detection/DetectionPreviewRenderer.kt`
- Test: `app/src/androidTest/java/rs/masumi/app/detection/PageBitmapDecoderTest.kt`
- Test: `app/src/androidTest/java/rs/masumi/app/detection/OnnxInputPreprocessorTest.kt`
- Test: `app/src/androidTest/java/rs/masumi/app/detection/DetectionPreviewRendererTest.kt`

- [ ] **Step 1: Write generated-image tests and run RED**

Assert normal/EXIF-rotated dimensions, exact CHW RGB `/255f` values and shape `[1,3,640,640]`, preview dimensions, approved colors, and unchanged source bytes.

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

- [ ] **Step 2: Implement decode, preprocessing, and rendering**

Use platform `android.media.ExifInterface` for all eight orientations and never rewrite the source. Bilinearly resize to `640 x 640` and fill a native direct `FloatBuffer` in CHW order. Copy the visible bitmap before drawing `#1976D2`, `#2E7D32`, and `#EF6C00` boxes plus class/confidence/short-ID labels; encode PNG bytes without recycling the caller bitmap.

- [ ] **Step 3: Build and commit**

```bash
./gradlew :app:assembleDebugAndroidTest
git add app/src/main/java/rs/masumi/app/detection app/src/androidTest/java/rs/masumi/app/detection
git commit -m "feat: prepare pages and detection previews"
```

## Task 6: Run the pinned ONNX detector

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/rs/masumi/app/detection/ComicDetector.kt`
- Create: `app/src/main/java/rs/masumi/app/detection/OnnxComicDetector.kt`
- Create: `app/src/main/java/rs/masumi/app/detection/DetectorModelProvider.kt`
- Test: `app/src/androidTest/java/rs/masumi/app/detection/DetectorModelProviderTest.kt`

- [ ] **Step 1: Add runtime and platform declarations**

Add `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`; add Internet, notifications, foreground service, and data-sync foreground permissions; declare the later non-exported data-sync service.

- [ ] **Step 2: Define detector boundaries and write fake-source tests**

```kotlin
interface ComicDetector : AutoCloseable {
    fun detect(page: DecodedPage): List<ModelQuery>
}
fun interface ComicDetectorFactory { fun open(modelFile: Path): ComicDetector }
fun interface DetectorModelProvider {
    fun acquire(installId: String, progress: (Long, Long) -> Unit): Path
}
```

Inject a `ModelStreamSource` and test correct bytes, failing streams, HTTP error mapping, and monotonic progress without network access.

- [ ] **Step 3: Implement ONNX and HTTPS adapters**

Validate exact input/output names, types, ranks, and 300 aligned queries. Close tensors/results deterministically. Use 15-second connect and 120-second read timeouts, require HTTP 2xx, and delegate bytes to the core store. Do not log bodies or redirect URLs.

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/rs/masumi/app/detection \
  app/src/androidTest/java/rs/masumi/app/detection/DetectorModelProviderTest.kt
git commit -m "feat: run pinned comic detector"
```

## Task 7: Orchestrate sequential detection with retry and recovery

**Files:**
- Create: `app/src/main/java/rs/masumi/app/detection/ProjectCatalog.kt`
- Create: `app/src/main/java/rs/masumi/app/detection/DetectionRunner.kt`
- Test: `app/src/androidTest/java/rs/masumi/app/detection/DetectionRunnerTest.kt`

- [ ] **Step 1: Write fake-detector runner tests**

Create temporary imported projects from generated images. Cover duplicate-source reuse, previews per order, unchanged source hashes, 300 raw queries, rebuilt-session retry, second-failure preservation, committed checkpoint reuse, page-boundary cancellation, and fatal model/source/publication errors.

- [ ] **Step 2: Implement discovery and synchronous runner**

Read strict manifests from direct project children and choose the newest valid project. Validate manifest/source SHA, compute keys, recover/create a job, acquire one model/session, and process unique page IDs sequentially. Journal `RUNNING`, decode/infer/post-process/render/checkpoint, then journal `COMMITTED`. Rebuild the session for one retry; preserve the source after the second page failure and continue. Publish the run before terminalizing the job. Recycle bitmaps and close the detector in `finally`.

- [ ] **Step 3: Build, run direct instrumentation, and commit**

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class rs.masumi.app.detection.DetectionRunnerTest \
  rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner
git add app/src/main/java/rs/masumi/app/detection app/src/androidTest/java/rs/masumi/app/detection
git commit -m "feat: orchestrate resumable page detection"
```

Expected: instrumentation reports `OK`; neither package is uninstalled.

## Task 8: Keep analysis alive in a foreground service

**Files:**
- Create: `app/src/main/java/rs/masumi/app/detection/DetectionForegroundService.kt`
- Create: `app/src/main/java/rs/masumi/app/detection/DetectionStatusBroadcast.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/rs/masumi/app/detection/DetectionStatusBroadcastTest.kt`

- [ ] **Step 1: Test safe status contracts**

Broadcasts must be package-scoped and contain only project/job/run status, counts, page order, and stable error code—never paths, model URLs, exception text, or bitmap bytes.

- [ ] **Step 2: Implement service and broadcast**

Call `startForeground` immediately, own one single-thread executor, reject concurrent projects, and use `AtomicBoolean` for page-boundary cancellation. Return `START_REDELIVER_INTENT` while active. Update notification plus explicit package broadcast for progress/terminal states. `onDestroy` closes the executor without deleting checkpoints.

- [ ] **Step 3: Compile and commit**

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebugAndroidTest
git add app/src/main/AndroidManifest.xml app/src/main/java/rs/masumi/app/detection \
  app/src/androidTest/java/rs/masumi/app/detection/DetectionStatusBroadcastTest.kt
git commit -m "feat: keep page detection recoverable"
```

## Task 9: Add progress and preview navigation UI

**Files:**
- Modify: `app/src/main/java/rs/masumi/app/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/colors.xml`
- Modify: `app/src/androidTest/java/rs/masumi/app/MainActivityLayoutTest.kt`

- [ ] **Step 1: Write failing layout tests**

Assert disabled-until-project analysis, hidden cancel, determinate progress, status, preview image, previous/next controls, page indicator, and three legend labels.

- [ ] **Step 2: Build layout and activity state**

Retain import controls. Discover latest project on launch, start/cancel the service, request notification permission immediately before first analysis on API 33+, register a non-exported receiver while started, and reload durable state on every progress event and `onResume`. Canonicalize preview paths under their project. Navigate in manifest order and show the original plus failure marker for preserved pages.

- [ ] **Step 3: Build, instrument, and commit**

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class rs.masumi.app.MainActivityLayoutTest \
  rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner
git add app/src/main app/src/androidTest/java/rs/masumi/app/MainActivityLayoutTest.kt
git commit -m "feat: inspect page detection results"
```

## Task 10: Verify the complete slice

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/foundation.md`
- Do not commit: model binaries, device output, screenshots, logs, corpus identifiers/counts/images/hashes, or absolute paths.

- [ ] **Step 1: Run complete gates**

```bash
./gradlew :pipeline-core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Expected: `BUILD SUCCESSFUL` with zero failed tests and lint errors.

- [ ] **Step 2: Replace-install and launch**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop rs.masumi.app.dev
adb shell monkey -p rs.masumi.app.dev 1
```

Expected: no uninstall; the existing imported project remains available.

- [ ] **Step 3: Run real full-project acceptance**

Acquire and verify the pinned model, analyze every manifest entry, require `COMMITTED` or `PRESERVED_SOURCE`, require 300 raw queries and decodable preview for each committed page, and recompute every source SHA. Keep timing/class/confidence/retry/preservation measurements only in ignored private notes.

- [ ] **Step 4: Prove restart, cancellation, and recall**

Terminate the app process mid-run without uninstall, reopen, and confirm committed pages are not inferred again. Cancel after a committed page and confirm no next page starts while the checkpoint remains. Navigate all previews and confirm dialogue/narration candidates are covered while orange free text remains protected.

- [ ] **Step 5: Update public docs and run privacy checks**

```bash
git diff --check
test -z "$(git ls-files | rg -i '\.(jpg|jpeg|png|webp|onnx|gguf|safetensors)$')"
rg -n -i 'api[_ -]?key|bearer |signed_url' README.md docs app pipeline-core --glob '!**/build/**'
test -z "$(rg -n -F "$HOME" README.md docs app pipeline-core --glob '!**/build/**')"
```

Expected: no corpus/model binary, secret, local path, hardware fact, or private identifier is tracked.

- [ ] **Step 6: Request review, re-verify, and push**

Use `superpowers:requesting-code-review`, fix concrete verified findings, then run:

```bash
./gradlew :pipeline-core:test :app:assembleDebug :app:lintDebug
git status --short --branch
git push origin codex/foundation
```

Expected: gates pass, final commits leave a clean worktree, and remote matches local `HEAD`.

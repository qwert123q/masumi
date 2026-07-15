# PaddleOCR-VL Vulkan Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Masumi's on-device PaddleOCR-VL runtime prefer a statically linked Vulkan backend, fall back safely to CPU during engine initialization, record the backend actually used for every OCR attempt, and prove a representative page completes within 180 seconds.

**Architecture:** `pipeline-core` owns the serializable execution-backend enum and cache identity. Android Kotlin owns the testable open policy—one Vulkan attempt followed by one CPU attempt for accelerator-specific initialization failures—while each native attempt owns and fully releases its model/projector resources. The JNI layer builds both GGML Vulkan and CPU, fixes one backend for each engine handle, and exposes the selected backend to Kotlin; inference remains sequential and keeps the current crop and quality policy.

**Tech Stack:** Kotlin 2.3, kotlinx.serialization, Android SDK 36, Android NDK 28.2, CMake 3.22, C++17/JNI, llama.cpp `b8935`, GGML Vulkan/CPU, mtmd, JUnit 4, Android instrumentation, ADB.

---

## File map

### Portable contracts and identity

- Modify `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt`: add the serializable actual execution backend and require it on every attempt artifact.
- Modify `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackage.kt`: publish the Vulkan-preferred runtime policy and new build contract.
- Modify `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt`: make fixtures use the new runtime policy and a concrete attempt backend.
- Modify `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJsonTest.kt`: prove the backend survives strict serialization.
- Modify `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrIdentityTest.kt`: prove backend policy changes invalidate page identity.
- Create `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrRuntimeContractTest.kt`: pin the public runtime identity and unchanged image-token budget contract.

### Android engine boundary

- Modify `app/src/main/java/rs/masumi/app/ocr/OcrEngine.kt`: expose the selected backend on every engine.
- Modify `app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt`: add backend preference, testable Vulkan-to-CPU open policy, JNI backend query, and CPU-only capability validation.
- Modify `app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt`: cover Vulkan success, unavailable accelerator, GPU initialization failure, double failure, backend mismatch, and CPU-only opening with fake bridges.

### Native runtime

- Modify `app/src/main/cpp/CMakeLists.txt`: enable statically linked GGML Vulkan, require the NDK shader compiler, and retain CPU.
- Modify `app/src/main/cpp/paddle_ocr_jni.cpp`: enumerate GPU and integrated-GPU devices, apply per-attempt GPU/CPU parameters, store the fixed backend, expose it through JNI, and use it for context offload.

### Orchestration, verification, and documentation

- Modify `app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt`: copy the engine's actual backend into successful and failed OCR attempts.
- Modify `app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt`: prove published attempts retain the actual backend.
- Modify `app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrSmokeTest.kt`: support an explicit CPU-only run and assert the expected real native backend.
- Modify `README.md`: describe Vulkan-preferred local OCR and CPU fallback without device-specific claims.
- Modify `docs/architecture/foundation.md`: document backend selection, audit field, sequential inference, and acceptance boundary.

---

### Task 1: Define execution backends and invalidate CPU-only cache identity

**Files:**
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackage.kt`
- Modify: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt`
- Modify: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrIdentityTest.kt`
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrRuntimeContractTest.kt`

- [ ] **Step 1: Write failing identity and pinned-runtime tests**

Extend the changed dependency list in `OcrIdentityTest`:

```kotlin
OcrIdentity.pageArtifactKey(
    "a".repeat(64),
    "b".repeat(64),
    dependencies.copy(runtime = dependencies.runtime.copy(backend = "cpu")),
),
```

Create `OcrRuntimeContractTest.kt`:

```kotlin
package rs.masumi.core.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl

class OcrRuntimeContractTest {
    @Test
    fun `pinned runtime publishes Vulkan preferred cache identity`() {
        val runtime = PinnedPaddleOcrVl.descriptor.runtime

        assertEquals("vulkan-preferred-cpu-fallback", runtime.backend)
        assertEquals("mtmd-vulkan-pref-t6-image16-v1", runtime.buildContract)
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrIdentityTest \
  --tests rs.masumi.core.ocr.OcrRuntimeContractTest
```

Expected: `OcrRuntimeContractTest` fails because the pinned runtime still reports `cpu`.

- [ ] **Step 3: Add the portable enum and runtime identity**

Add to `OcrContracts.kt`:

```kotlin
@Serializable
enum class OcrExecutionBackend {
    VULKAN,
    CPU,
}
```

Update `OcrFixtures.dependencies()`:

```kotlin
backend = "vulkan-preferred-cpu-fallback",
buildContract = "mtmd-vulkan-pref-t6-image16-v1",
```

Update `PinnedPaddleOcrVl.descriptor.runtime` with the same two runtime strings. Do not change `packageSha256`, model descriptors, generation settings, crop policy, quality thresholds, or `OCR_SCHEMA_VERSION`.

- [ ] **Step 4: Run focused and complete core tests**

Run:

```bash
./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrIdentityTest \
  --tests rs.masumi.core.ocr.OcrRuntimeContractTest && \
./gradlew :pipeline-core:test
```

Expected: all `pipeline-core` tests pass, and page keys differ between CPU-only and Vulkan-preferred runtime records.

- [ ] **Step 5: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt \
  pipeline-core/src/main/kotlin/rs/masumi/core/modelpackage/OcrModelPackage.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrIdentityTest.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrRuntimeContractTest.kt
git commit -m "feat: identify OCR execution backend"
```

### Task 2: Make Vulkan preference and CPU fallback testable in Kotlin

**Files:**
- Modify: `app/src/main/java/rs/masumi/app/ocr/OcrEngine.kt`
- Modify: `app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt`
- Modify: `app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt`
- Modify: `app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt`

- [ ] **Step 1: Write failing bridge-policy tests**

Extend the test bridge so `create` records `preferGpu`, returns queued handles, and `executionBackend` returns the configured backend:

```kotlin
private class OpenPolicyBridge(
    handles: List<Long>,
    private val backends: Map<Long, String>,
) : NativeOcrBridge {
    private val remainingHandles = ArrayDeque(handles)
    val gpuPreferences = mutableListOf<Boolean>()
    val destroyedHandles = mutableListOf<Long>()

    override fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long {
        gpuPreferences += preferGpu
        return remainingHandles.removeFirst()
    }

    override fun executionBackend(handle: Long): String? = backends[handle]
    override fun recognize(handle: Long, rgb: ByteArray, width: Int, height: Int, prompt: String,
        maximumGeneratedTokens: Int, repetitionPenalty: Double): String = error("unused")
    override fun cancel(handle: Long) = Unit
    override fun destroy(handle: Long) { destroyedHandles += handle }
}
```

Create real temporary model/projector files in each test and add these cases:

```kotlin
@Test
fun VulkanSuccessDoesNotOpenCpu() = withModelFiles { model, projector ->
    val bridge = OpenPolicyBridge(listOf(7L), mapOf(7L to "VULKAN"))
    NativePaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
        assertEquals(OcrExecutionBackend.VULKAN, engine.executionBackend)
    }
    assertEquals(listOf(true), bridge.gpuPreferences)
}

@Test
fun unavailableAcceleratorFallsBackToCpuOnce() = withModelFiles { model, projector ->
    val bridge = OpenPolicyBridge(listOf(-5L, 8L), mapOf(8L to "CPU"))
    NativePaddleOcrEngine.openWithBridge(model, projector, bridge).use { engine ->
        assertEquals(OcrExecutionBackend.CPU, engine.executionBackend)
    }
    assertEquals(listOf(true, false), bridge.gpuPreferences)
}

@Test
fun gpuModelInitializationFailureFallsBackToCpuOnce() = withModelFiles { model, projector ->
    val bridge = OpenPolicyBridge(listOf(-1L, 9L), mapOf(9L to "CPU"))
    NativePaddleOcrEngine.openWithBridge(model, projector, bridge).close()
    assertEquals(listOf(true, false), bridge.gpuPreferences)
}

@Test
fun cpuOnlyOpenNeverRequestsGpu() = withModelFiles { model, projector ->
    val bridge = OpenPolicyBridge(listOf(10L), mapOf(10L to "CPU"))
    NativePaddleOcrEngine.openCpuOnlyWithBridge(model, projector, bridge).close()
    assertEquals(listOf(false), bridge.gpuPreferences)
}

@Test
fun modelCapabilityFailuresDoNotRetryCpu() = withModelFiles { model, projector ->
    listOf(-3L to OcrEngineErrorCode.VISION_UNSUPPORTED, -4L to OcrEngineErrorCode.TOKENIZE)
        .forEach { (handle, expectedCode) ->
            val bridge = OpenPolicyBridge(listOf(handle), emptyMap())
            val failure = captureEngineError {
                NativePaddleOcrEngine.openWithBridge(model, projector, bridge)
            }
            assertEquals(expectedCode, failure.code)
            assertEquals(listOf(true), bridge.gpuPreferences)
        }
}

@Test
fun failedCpuRetryReportsCpuError() = withModelFiles { model, projector ->
    val bridge = OpenPolicyBridge(listOf(-5L, -2L), emptyMap())
    val failure = captureEngineError {
        NativePaddleOcrEngine.openWithBridge(model, projector, bridge)
    }
    assertEquals(OcrEngineErrorCode.PROJECTOR_LOAD, failure.code)
    assertEquals(listOf(true, false), bridge.gpuPreferences)
}

@Test
fun unknownBackendDestroysHandleAndFailsSafely() = withModelFiles { model, projector ->
    val bridge = OpenPolicyBridge(listOf(11L), mapOf(11L to "UNKNOWN"))
    val failure = captureEngineError {
        NativePaddleOcrEngine.openWithBridge(model, projector, bridge)
    }
    assertEquals(OcrEngineErrorCode.CONTEXT, failure.code)
    assertEquals(listOf(11L), bridge.destroyedHandles)
}
```

Add this local helper:

```kotlin
private fun captureEngineError(block: () -> Unit): OcrEngineException = try {
    block()
    throw AssertionError("Expected OCR engine open failure")
} catch (failure: OcrEngineException) {
    failure
}
```

- [ ] **Step 2: Compile the Android tests and verify RED**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation fails because the bridge has no preference/backend methods and the engine exposes no selected backend.

- [ ] **Step 3: Add the engine property, preference policy, and JNI bridge methods**

In `OcrEngine.kt`, add:

```kotlin
interface OcrEngine : AutoCloseable {
    val executionBackend: OcrExecutionBackend
    fun recognize(request: OcrEngineRequest, cancellation: () -> Boolean): OcrEngineResult
    fun cancel()
}
```

In `NativePaddleOcrEngine.kt`, change the bridge boundary to:

```kotlin
internal interface NativeOcrBridge {
    fun create(modelPath: String, projectorPath: String, preferGpu: Boolean): Long
    fun executionBackend(handle: Long): String?
    // recognize, cancel, and destroy retain their current signatures
}

internal enum class NativeBackendPolicy {
    VULKAN_PREFERRED,
    CPU_ONLY,
}
```

Store `override val executionBackend: OcrExecutionBackend` in the engine constructor. Implement `openWithBridge` with this exact attempt order:

```kotlin
internal fun openWithBridge(
    model: Path,
    projector: Path,
    bridge: NativeOcrBridge,
    policy: NativeBackendPolicy = NativeBackendPolicy.VULKAN_PREFERRED,
): NativePaddleOcrEngine
```

```kotlin
val preferences = when (policy) {
    NativeBackendPolicy.VULKAN_PREFERRED -> listOf(true, false)
    NativeBackendPolicy.CPU_ONLY -> listOf(false)
}
for (preferGpu in preferences) {
    val handle = bridge.create(model.toString(), projector.toString(), preferGpu)
    if (handle > 0L) return engineFromPositiveHandle(handle, bridge)
    val code = mapCreateFailure(handle)
    val retryOnCpu = preferGpu && code in setOf(
        OcrEngineErrorCode.MODEL_LOAD,
        OcrEngineErrorCode.PROJECTOR_LOAD,
        OcrEngineErrorCode.CONTEXT,
        OcrEngineErrorCode.ACCELERATOR_UNAVAILABLE,
    )
    if (!retryOnCpu) throw OcrEngineException(code)
}
throw OcrEngineException(OcrEngineErrorCode.CONTEXT)
```

Add `ACCELERATOR_UNAVAILABLE` with a sanitized message to `OcrEngineErrorCode`; it is an internal open-attempt result and must not escape when CPU succeeds. Map native `-5L` to it. Parse only `VULKAN` and `CPU`; destroy a positive handle before throwing if the backend query fails or returns any other value.

Keep public `open(model, projector)` Vulkan-preferred. Add internal `openCpuOnly(model, projector)` and `openCpuOnlyWithBridge(...)`. Change `NativePaddleOcrCapabilityValidator` to call `openCpuOnly`, so GPU pressure cannot determine package validity.

Change `JniNativeOcrBridge` visibility from `private` to `internal` so instrumentation can verify every declared JNI method without exposing the bridge outside the app module.

Add `override val executionBackend = OcrExecutionBackend.CPU` to the existing fake engine in `OcrRunnerTest` so the complete Android-test source set remains compilable. Task 5 will parameterize this value for artifact assertions.

- [ ] **Step 4: Run the focused instrumentation contract tests**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.ocr.NativePaddleOcrContractTest
```

Expected: every bridge-policy, safe-error, cancellation, and malformed-RGB test passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/rs/masumi/app/ocr/OcrEngine.kt \
  app/src/main/java/rs/masumi/app/ocr/NativePaddleOcrEngine.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt
git commit -m "feat: add Vulkan preferred engine policy"
```

### Task 3: Compile the static Vulkan backend into the Android library

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Record the CPU-only build as the failing baseline**

Run:

```bash
rg -n 'GGML_VULKAN|Vulkan_GLSLC_EXECUTABLE' app/src/main/cpp/CMakeLists.txt
```

Expected: no matches; the build does not declare the approved Vulkan contract.

- [ ] **Step 2: Enable static Vulkan and require the NDK shader compiler**

Add before `add_subdirectory("${LLAMA_DIR}" ...)`:

```cmake
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

if(ANDROID)
    set(MASUMI_NDK_GLSLC
        "${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
    if(NOT EXISTS "${MASUMI_NDK_GLSLC}")
        message(FATAL_ERROR "Android NDK glslc is required for the Vulkan OCR backend")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${MASUMI_NDK_GLSLC}"
        CACHE FILEPATH "Android NDK glslc" FORCE)
endif()
```

Keep `BUILD_SHARED_LIBS OFF`, CPU enabled, OpenMP disabled, and the existing pinned mtmd patch. Do not enable dynamic backend loading or another accelerator backend.

- [ ] **Step 3: Build the native library**

Run: `./gradlew :app:externalNativeBuildDebug`

Expected: CMake reports `Including Vulkan backend` and `Vulkan found`; the task succeeds for `arm64-v8a`.

- [ ] **Step 4: Verify Vulkan is linked into `libmasumi_ocr.so`**

Run:

```bash
SO="$(find app/build/intermediates/cxx/Debug -path '*/obj/arm64-v8a/libmasumi_ocr.so' -print -quit)"
test -n "$SO"
"$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" -Ws "$SO" \
  | rg 'ggml_backend_vk_reg'
```

Expected: one defined `ggml_backend_vk_reg` symbol is present. A missing symbol fails this task even if Gradle succeeds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/CMakeLists.txt
git commit -m "build: enable Android Vulkan OCR backend"
```

### Task 4: Implement fixed native Vulkan and CPU engine attempts

**Files:**
- Modify: `app/src/main/cpp/paddle_ocr_jni.cpp`
- Modify: `app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt`

- [ ] **Step 1: Write and run a failing JNI-linkage test**

Make `JniNativeOcrBridge` `internal` in Task 2, then add:

```kotlin
@Test
fun backendQueryJniMethodIsLinked() {
    assertEquals(null, JniNativeOcrBridge.executionBackend(0L))
}
```

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.ocr.NativePaddleOcrContractTest#backendQueryJniMethodIsLinked
```

Expected: the test fails with `UnsatisfiedLinkError` because the declared `executionBackend` JNI method has no native implementation.

- [ ] **Step 2: Add backend representation and accelerator enumeration**

Include `ggml-backend.h` and define:

```cpp
enum class ExecutionBackend {
    Vulkan,
    Cpu,
};

const char * backend_name(ExecutionBackend backend) {
    return backend == ExecutionBackend::Vulkan ? "VULKAN" : "CPU";
}

bool has_accelerator_device() {
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        const auto type = ggml_backend_dev_type(ggml_backend_dev_get(index));
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU ||
            type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            return true;
        }
    }
    return false;
}
```

Add `ExecutionBackend backend = ExecutionBackend::Cpu;` to `EngineHandle`. Keep the existing single inference mutex and cancellation flag.

- [ ] **Step 3: Make one native create call own exactly one backend attempt**

Change JNI `create` to accept `jboolean prefer_gpu`. Copy the two JNI strings into `std::string` values and release both JNI buffers before model initialization. After the existing once-only `llama_backend_init`, return `-5` if GPU was requested but no `GPU` or `IGPU` device is registered.

Build one `std::unique_ptr<EngineHandle>` and set:

```cpp
handle->backend = prefer_gpu ? ExecutionBackend::Vulkan : ExecutionBackend::Cpu;
llama_model_params model_params = llama_model_default_params();
model_params.n_gpu_layers = prefer_gpu ? 1000 : 0;
model_params.use_mmap = true;

mtmd_context_params vision_params = mtmd_context_params_default();
vision_params.use_gpu = prefer_gpu;
vision_params.print_timings = false;
vision_params.n_threads = kThreadCount;
vision_params.warmup = false;
vision_params.image_min_tokens = kMinimumImageTokens;
vision_params.image_max_tokens = kMaximumImageTokens;
```

Retain the existing safe return codes. Because the handle remains a `unique_ptr` until all checks pass, any GPU model, template, projector, or vision failure releases partial resources before Kotlin starts the CPU attempt.

- [ ] **Step 4: Fix context offload and expose the selected backend**

In `run_inference`, replace the CPU constant with:

```cpp
context_params.offload_kqv = handle->backend == ExecutionBackend::Vulkan;
```

Add JNI:

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_rs_masumi_app_ocr_JniNativeOcrBridge_executionBackend(
    JNIEnv * env,
    jobject,
    jlong handle_value) {
    EngineHandle * handle = from_handle(handle_value);
    if (handle == nullptr) return nullptr;
    return env->NewStringUTF(backend_name(handle->backend));
}
```

Do not add inference-time fallback. Decode failure, cancellation, and UTF-8 failure continue through the current JSON error protocol.

- [ ] **Step 5: Build and run the Kotlin contract suite**

Run:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.ocr.NativePaddleOcrContractTest
```

Expected: native linking succeeds; all engine policy tests pass; no `UnsatisfiedLinkError` occurs for either JNI method.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/paddle_ocr_jni.cpp \
  app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrContractTest.kt
git commit -m "feat: select native OCR execution backend"
```

### Task 5: Record actual backends on successful and failed OCR attempts

**Files:**
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt`
- Modify: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt`
- Modify: `pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJsonTest.kt`
- Modify: `app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt`
- Modify: `app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt`

- [ ] **Step 1: Write failing serialization and published-artifact tests**

Add this assertion to `OcrJsonTest` after decoding:

```kotlin
val decoded = OcrJson().decodePageArtifact(encoded)
assertEquals(OcrExecutionBackend.VULKAN, decoded.regions.single().attempts.single().executionBackend)
```

Add a backend parameter to the test `runner` helper and expose it from the fake engine:

```kotlin
private fun runner(
    workspace: Path,
    backend: OcrExecutionBackend = OcrExecutionBackend.VULKAN,
    recognize: (OcrEngineRequest) -> OcrEngineResult,
): OcrRunner
```

```kotlin
override val executionBackend: OcrExecutionBackend = backend
```

Add this test, using the existing project/detection setup and JSON reader:

```kotlin
@Test
fun publishedAttemptsRecordActualEngineBackend() {
    withWorkspace { workspace ->
        createProjectAndDetection(workspace, candidateCount = 1, duplicatePage = false)
        val completed = runner(workspace, OcrExecutionBackend.CPU) { result("今日は", 0.9) }
            .run(PROJECT_ID, { false }) { }
        val pagePath = completed.publishedDirectory!!
            .resolve("pages/${completed.job.pages.single().pageId}/ocr.json")
        val page = OcrJson().decodePageArtifact(Files.readString(pagePath))

        assertEquals(
            OcrExecutionBackend.CPU,
            page.regions.single().attempts.single().executionBackend,
        )
    }
}
```

Extend `repeatedFailurePreservesOneRegionAndContinuesWithTheNext` to decode the page and assert all three failed attempts also contain the fake engine's backend.

- [ ] **Step 2: Compile and verify RED**

Run:

```bash
./gradlew :pipeline-core:test --tests rs.masumi.core.ocr.OcrJsonTest && \
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: compilation fails because `OcrAttemptArtifact` has no `executionBackend` property.

- [ ] **Step 3: Require and propagate the engine backend in both attempt paths**

Add this required field to `OcrAttemptArtifact` immediately before `strategy`:

```kotlin
val executionBackend: OcrExecutionBackend,
```

Set `executionBackend = OcrExecutionBackend.VULKAN` in `OcrFixtures.attempt()`.

Capture `engine.executionBackend` in `recognizeRegion` and pass it to both conversion helpers:

```kotlin
result.toAttempt(
    descriptor = descriptor,
    normalizedText = qualityEvaluator.normalize(result.rawText),
    executionBackend = engine.executionBackend,
)
```

```kotlin
failure.toFailedAttempt(
    descriptor = descriptor,
    crop = rendered,
    executionBackend = engine.executionBackend,
)
```

Add `executionBackend: OcrExecutionBackend` to both helper signatures and constructors. Do not infer the backend from the runtime preference string.

- [ ] **Step 4: Run focused and complete instrumentation tests**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.ocr.OcrRunnerTest && \
./gradlew :pipeline-core:test :app:connectedDebugAndroidTest
```

Expected: the focused runner tests and complete instrumentation suite pass; successful and failed attempts serialize their actual backend.

- [ ] **Step 5: Commit**

```bash
git add pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrFixtures.kt \
  pipeline-core/src/test/kotlin/rs/masumi/core/ocr/OcrJsonTest.kt \
  app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt \
  app/src/androidTest/java/rs/masumi/app/ocr/OcrRunnerTest.kt
git commit -m "feat: audit OCR execution backend"
```

### Task 6: Prove real Vulkan execution, update public documentation, and run acceptance

**Files:**
- Modify: `app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrSmokeTest.kt`
- Modify: `README.md`
- Modify: `docs/architecture/foundation.md`

- [ ] **Step 1: Extend the opt-in smoke test with explicit backend expectations**

Read these instrumentation arguments:

```kotlin
val expectedBackend = arguments.getString("nativeOcrExpectedBackend")
    ?.let(OcrExecutionBackend::valueOf)
    ?: OcrExecutionBackend.VULKAN
val forceCpu = arguments.getString("nativeOcrForceCpu").toBoolean()
```

Open through `openCpuOnly` only when `forceCpu` is true; otherwise use public `open`. Before inference assert:

```kotlin
assertEquals(expectedBackend, engine.executionBackend)
```

Keep all current assertions for Japanese text, token/probability alignment, UTF-8, `visualTokenCount in 1..16`, and non-truncation. Print only aggregate timing and token counts; never print model paths, device identifiers, source text from imported pages, or project IDs.

- [ ] **Step 2: Run the CPU-only native smoke as a safety proof**

Set local environment variables to the two already verified GGUF files, stage them in the debug app's private directory, and run:

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
test -f "$MASUMI_OCR_MODEL" && test -f "$MASUMI_OCR_PROJECTOR"
adb push -Z "$MASUMI_OCR_MODEL" /data/local/tmp/masumi-model.gguf
adb push -Z "$MASUMI_OCR_PROJECTOR" /data/local/tmp/masumi-projector.gguf
adb shell run-as rs.masumi.app.dev mkdir -p files/native-smoke
adb shell run-as rs.masumi.app.dev cp /data/local/tmp/masumi-model.gguf files/native-smoke/model.gguf
adb shell run-as rs.masumi.app.dev cp /data/local/tmp/masumi-projector.gguf files/native-smoke/projector.gguf
adb shell rm -f /data/local/tmp/masumi-model.gguf /data/local/tmp/masumi-projector.gguf
adb shell am instrument -w -r \
  -e class rs.masumi.app.ocr.NativePaddleOcrSmokeTest \
  -e ocrModelPath /data/user/0/rs.masumi.app.dev/files/native-smoke/model.gguf \
  -e ocrProjectorPath /data/user/0/rs.masumi.app.dev/files/native-smoke/projector.gguf \
  -e nativeOcrForceCpu true \
  -e nativeOcrExpectedBackend CPU \
  rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)` and the backend assertion is `CPU`.

- [ ] **Step 3: Run the Vulkan native smoke**

Run:

```bash
adb shell am instrument -w -r \
  -e class rs.masumi.app.ocr.NativePaddleOcrSmokeTest \
  -e ocrModelPath /data/user/0/rs.masumi.app.dev/files/native-smoke/model.gguf \
  -e ocrProjectorPath /data/user/0/rs.masumi.app.dev/files/native-smoke/projector.gguf \
  -e nativeOcrExpectedBackend VULKAN \
  rs.masumi.app.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: `OK (1 test)`, selected backend `VULKAN`, valid Japanese output, 1–16 visual tokens, matching non-empty token/probability arrays, and no native crash or out-of-memory failure. A CPU fallback fails this acceptance step.

- [ ] **Step 4: Update public documentation**

Change README and foundation architecture wording from “sequential arm64 CPU OCR” to “sequential arm64 Vulkan-preferred OCR with CPU initialization fallback.” Document that:

- capability validation is CPU-only and backend-neutral;
- an engine backend is fixed after open;
- each attempt artifact records `VULKAN` or `CPU`;
- model files remain app-private and excluded from Git;
- inference concurrency and the 16-token visual budget are unchanged;
- CPU fallback is an availability path, not the performance acceptance path.

Do not add device models, local paths, imported corpus names, recognized page content, private project identifiers, or measured device logs.

- [ ] **Step 5: Run the complete clean verification gate**

Run:

```bash
./gradlew clean :pipeline-core:test :app:assembleDebug :app:lint \
  :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest && \
git diff --check
```

Expected: every Gradle task succeeds, lint has no new errors, all device tests pass, and `git diff --check` is silent.

- [ ] **Step 6: Run one external representative-page acceptance job**

First let the model provider finish model installation outside the timed run. Then import a fresh one-page representative project through the existing app UI, complete detection, and keep its identifier only in the shell environment. The fresh project must have no OCR job, staging directory, or OCR artifact before timing begins:

```bash
test -n "$MASUMI_PROJECT_ID"
START_EPOCH="$(date +%s)"
adb shell am start-foreground-service \
  -n rs.masumi.app.dev/rs.masumi.app.ocr.OcrForegroundService \
  -a rs.masumi.app.action.START_OCR \
  --es projectId "$MASUMI_PROJECT_ID"
```

Wait for the OCR notification to reach a terminal state, then record `END_EPOCH=$(date +%s)` outside the repository and inspect the published `ocr.json` through `adb exec-out run-as rs.masumi.app.dev`. Acceptance requires:

- `END_EPOCH - START_EPOCH <= 180` for the complete page;
- every attempted region reports `executionBackend: "VULKAN"`;
- no `AndroidRuntime`, native crash, or out-of-memory record appears in `adb logcat`;
- source and detection hashes remain unchanged;
- the three-crop terminal policy remains active and uncertain text remains preserved;
- recognized output on the same crops is not materially worse than the prior CPU safety run.

Keep page files, OCR text, timing logs, project IDs, and device data outside Git. If confirmed Vulkan execution still exceeds 180 seconds, stop performance tuning in this plan and open the deferred micro-batching/contact-sheet design instead of raising concurrency.

- [ ] **Step 7: Commit**

```bash
git add app/src/androidTest/java/rs/masumi/app/ocr/NativePaddleOcrSmokeTest.kt \
  README.md docs/architecture/foundation.md
git commit -m "test: verify Vulkan OCR runtime"
```

- [ ] **Step 8: Verify repository privacy and final branch state**

Run:

```bash
git grep -nF "$HOME" -- README.md docs pipeline-core app/src || true
rg -n '(deviceModel|deviceSerial|projectId[[:space:]]*=)' \
  README.md docs pipeline-core app/src || true
git status --short --branch
git log --oneline --decorate -8
```

Expected: the privacy scan finds no absolute home path or assigned device/project identifier; manual review finds no real corpus name; the worktree is clean and the task commits are visible.

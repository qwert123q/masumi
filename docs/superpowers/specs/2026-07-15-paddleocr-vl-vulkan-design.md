# Masumi PaddleOCR-VL Vulkan Backend Design

Date: 2026-07-15

Status: Approved, quality amendment accepted after first-page diagnosis

## Purpose

The current Android PaddleOCR-VL runtime is not yet a valid quality baseline: the detector passed `orig_size` as height-width instead of the model's width-height contract, and native OCR forced every crop into a 16-token visual budget. The detector defect displaces and clips boxes on every non-square page; the fixed OCR budget discards character detail regardless of crop shape. This slice first restores geometric and visual fidelity, then changes native execution to prefer Vulkan and fall back to CPU when Vulkan cannot be initialized.

The target remains a complete page in at most 180 seconds on the reference Android device, without weakening source preservation or OCR quality gates. CPU fallback preserves availability; it is not considered performance acceptance.

This slice does not change translation, cleanup, inpainting, typesetting, export, detector thresholds, crop geometry, or page concurrency. It also does not introduce region micro-batching or whole-page OCR.

## Input-shape and reading-mode invariants

- Every detector invocation passes the current decoded page's actual `[width, height]`; pages in one title need not share dimensions or aspect ratio.
- Every OCR invocation passes the current crop's actual width and height. The projector metadata dynamically chooses its bounded visual-token count; Masumi does not impose a global fixed token count.
- Horizontal, vertical, square, panoramic, and long source pages use the same coordinate contract.
- Webtoon scrolling is a reading and later composition choice. It does not rescale, merge, reorder, or otherwise influence per-page detection or OCR.
- OCR artifacts continue to store each attempt's actual source dimensions and visual-token count for auditability.

## Selected approach

Masumi will compile both Vulkan and CPU GGML backends into the `arm64-v8a` native library. Each OCR engine open follows one deterministic policy:

1. Initialize llama.cpp and inspect its registered backend devices.
2. If a Vulkan GPU or integrated-GPU device is available, attempt a complete GPU engine initialization.
3. If GPU model, context, or multimodal-projector initialization fails, release every partially created native resource and retry once with the CPU configuration.
4. If CPU initialization also fails, return the existing sanitized model, projector, or context error.
5. Once an engine has opened, keep its selected backend fixed for its lifetime.

There is no silent GPU-to-CPU switch during an individual inference attempt. A decode or inference failure after initialization is recorded through the existing region retry and source-preservation rules. This makes GPU defects observable and prevents one attempt from mixing execution paths.

## Build contract

The Android CMake build will enable the statically linked GGML Vulkan backend while retaining the CPU backend. It will use the Android NDK's Vulkan headers, loader interface, and shader compiler, and will continue producing only the existing supported ABI.

The build must fail clearly when the required Vulkan build tools are unavailable; it must not silently publish a CPU-only binary under a Vulkan build identity. After `llama_backend_init`, native code will enumerate the backend registry rather than infer GPU support from Android model names or operating-system version strings.

The native runtime dependency identity changes from a CPU-only backend to:

- backend policy: `vulkan-preferred-cpu-fallback`;
- build contract: `mtmd-vulkan-pref-t6-image-default-v1`.

This identity participates in OCR artifact keys and therefore invalidates CPU-only or fixed-16-token OCR cache entries safely. Detector preprocessing identity also records the `WIDTH_HEIGHT` original-size order, invalidating geometrically incorrect detection artifacts. The thread count remains unchanged; actual visual-token counts are measured per crop.

## Native backend selection

Native code will represent the active backend explicitly as `VULKAN` or `CPU`. The JNI engine-creation call accepts a backend preference rather than hard-coding GPU use.

The preferred GPU configuration is:

- `n_gpu_layers = 1000`, allowing all supported model layers to be offloaded;
- multimodal projector `use_gpu = true`;
- context `offload_kqv = true`.

The fallback CPU configuration is:

- `n_gpu_layers = 0`;
- multimodal projector `use_gpu = false`;
- context `offload_kqv = false`.

GPU availability is established through the llama.cpp backend-device API and a device whose reported type is `GPU` or `IGPU`. Successful device discovery alone is insufficient: the GPU path is selected only after the language model, context, and projector all initialize successfully.

Every failed GPU-open path must free the context, projector, model, backend-owned resources, and temporary error state before CPU initialization begins. The engine handle records the backend that actually opened and exposes it through JNI.

## Kotlin contracts and auditability

`NativePaddleOcrEngine.open` will request the Vulkan-preferred policy for real OCR work. Model-package capability validation will deliberately open a CPU-only validation handle. Package validity must depend on model structure and file integrity, not on temporary GPU memory pressure or Vulkan availability.

The portable OCR attempt artifact will record the actual execution backend as `VULKAN` or `CPU`, including failed attempts when an engine backend had already been selected. This field provides evidence for performance tests and prevents a successful CPU fallback from being mistaken for Vulkan acceleration. Backend preference is not derived from, and artifacts do not store, a device name.

The existing constraints remain unchanged:

- one loaded engine and one active native context per OCR job;
- one crop inference at a time;
- cooperative cancellation between and during token generation;
- no page-level or region-level inference concurrency;
- region checkpoints remain independently resumable;
- source images and detection artifacts remain immutable.

## Failure handling

| Failure point | Required behavior |
| --- | --- |
| No Vulkan GPU device is registered | Open CPU directly and report `CPU` as the selected backend. |
| GPU model, context, or projector initialization fails | Fully release the partial GPU engine, retry CPU once, and report the actual backend. |
| CPU initialization fails | Return the existing sanitized initialization error; do not start OCR. |
| GPU inference or decode fails after open | Keep the engine on Vulkan and apply the existing crop-attempt retry and terminal-state policy. |
| Cancellation occurs | Stop through the existing cooperative cancellation path and release transient resources. |
| Native capability validation fails transiently | Preserve already verified model-package files and return a safe capability error. |

The model store must never delete length-, digest-, and metadata-verified files merely because Vulkan initialization or a transient native capability check failed.

## Test strategy

Implementation follows test-first development at each contract boundary:

1. Update portable dependency-identity and OCR-attempt serialization tests so they fail until backend policy and actual backend are represented.
2. Add Kotlin bridge tests with a fake native bridge for Vulkan success, unavailable-device CPU selection, GPU-open failure followed by CPU success, double failure, and backend reporting.
3. Add native lifecycle coverage where practical and compile the Vulkan and CPU paths in the Android build.
4. Extend the opt-in real-native instrumentation smoke test with an expected-backend argument. In Vulkan acceptance mode it must assert `VULKAN`, produce Japanese OCR output, expose valid visual-token counts and token probabilities, and finish without a native crash.
5. Add a non-square-page regression test for detector `orig_size`, plus cache-identity coverage for the coordinate-order contract.
6. Run all portable tests, Android unit tests, lint, debug assembly, Android-test assembly, and the complete device instrumentation suite.

Performance evidence is collected outside the public repository from representative imported pages. The acceptance run must demonstrate:

- the reported backend is `VULKAN` rather than an unnoticed CPU fallback;
- the source and detection inputs are unchanged;
- no native crash or out-of-memory failure occurs;
- a complete page finishes within 180 seconds;
- all required first-page regions reach a terminal recognized or preserved state, with no pending regions hidden as success;
- the existing three-crop quality and source-preservation policy remains active;
- OCR output on the same representative crops is not materially worse than the CPU baseline.

Raw pages, recognized content, device identifiers, local paths, API credentials, private project IDs, and device-specific timing logs are not committed.

## Falsifier and deferred work

If a representative page still exceeds 180 seconds on confirmed Vulkan execution while using the model-default dynamic visual budget, this design is falsified as the complete performance solution. The next step is a separate design for contact-sheet or true micro-batch inference, with explicit crop-to-result mapping and quality evaluation.

Masumi will not respond to a failed Vulkan benchmark by increasing page concurrency. It will also not switch to whole-page OCR in this slice because whole-page output weakens deterministic region mapping, retry isolation, checkpoint recovery, and small-text quality. Any future explicit token override requires measured quality evidence and a new cache identity.

## Completion criteria

This slice is complete only when:

- the native library contains functional Vulkan and CPU backends;
- non-square pages use their actual width-height order and old incorrect detection caches are not reusable;
- each variable-sized OCR crop uses the projector's model-default dynamic visual budget;
- real OCR prefers Vulkan and deterministically falls back only during engine initialization;
- capability validation remains CPU-only and backend-neutral;
- every OCR attempt records the actual selected backend;
- CPU-only cache identity is invalidated by the new runtime contract;
- all automated and device tests pass;
- an external representative-page run confirms Vulkan execution and the 180-second page target;
- no private corpus, device, or user information is introduced into the repository.

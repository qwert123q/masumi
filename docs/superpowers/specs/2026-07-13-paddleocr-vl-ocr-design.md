# Masumi PaddleOCR-VL OCR Design

Date: 2026-07-13

Status: Implemented and frozen

## Purpose

This slice turns a published page-detection run into a versioned, resumable OCR run. It consolidates the detector's text candidates, associates text with bubble context, crops source pixels without modifying them, recognizes Japanese text locally with PaddleOCR-VL 1.6, and publishes strict OCR JSON, annotated previews, and a terminal report.

The slice does not translate text, classify unresolved free text as narration or sound effects, generate cleanup masks, inpaint artwork, typeset translated text, or export flattened pages. A failed or uncertain OCR region remains explicitly protected so later stages cannot mistake uncertainty for an empty page.

The slice is successful when every eligible detection region reaches a durable terminal state without manual confirmation, committed regions survive process recreation, the source objects and detection artifacts remain unchanged, and real-device measurements demonstrate bounded memory and steady-state page latency.

## Selected approach

PaddleOCR-VL 1.6 is the sole primary OCR engine for this slice. The Android application runs the same GGUF language model and multimodal projector used by the upstream local workflow through a pinned llama.cpp `mtmd` runtime. The engine runs completely on the device after its model package has been installed.

The engine is behind a narrow `OcrEngine` interface so a later slice may add a fallback without changing OCR artifacts or orchestration. This slice does not ship Manga OCR or a network OCR provider.

The choice optimizes recognition quality and parity with the mature desktop workflow over APK size, initial model-download time, or implementation simplicity.

## Model and runtime package

The OCR model package is pinned to immutable upstream metadata:

| Field | Value |
| --- | --- |
| Repository | `PaddlePaddle/PaddleOCR-VL-1.6-GGUF` |
| Upstream revision | `511b09642bb324401f15f97cc23bc67e8f0a291d` |
| Language model file | `PaddleOCR-VL-1.6-GGUF.gguf` |
| Language model byte length | `935769056` |
| Projector file | `PaddleOCR-VL-1.6-GGUF-mmproj.gguf` |
| Projector byte length | `881770560` |
| License | Apache-2.0 |
| Native runtime | `llama.cpp` tag `b8935` |
| Prompt | `OCR:` |
| Maximum new tokens | `256` |
| Repetition penalty | `1.2` |

Model binaries and llama.cpp source are not committed to this repository. The model is downloaded to app-private storage on first use. The Android build pins the llama.cpp source tag and compiles only the `arm64-v8a` CPU backend plus the multimodal `mtmd` support required by this engine.

The model store uses one package directory containing both files and metadata:

```text
workspace/
└── models/
    ├── .staging/
    │   └── <storage-revision>/
    │       ├── PaddleOCR-VL-1.6-GGUF.gguf.part
    │       └── PaddleOCR-VL-1.6-GGUF-mmproj.gguf.part
    └── paddlepaddle--paddleocr-vl-1.6-gguf/
        └── <storage-revision>/
            ├── PaddleOCR-VL-1.6-GGUF.gguf
            ├── PaddleOCR-VL-1.6-GGUF-mmproj.gguf
            └── package.json
```

The storage revision is a pinned human-readable package identifier. `package.json` records both filenames and byte lengths, the upstream package revision, embedded model capabilities validated by native code, the llama.cpp runtime revision, compile backend, and acquisition time. It never records a temporary download URL, response headers, absolute path, or device information.

### Resumable installation

Each model file has an app-owned `*.part` file under a stable storage-revision staging directory, so a new OCR job can resume a download started by an earlier job. The expected URL, revision, filename, and length come from the pinned descriptor in code; the actual partial length is the durable downloaded-byte checkpoint. Installation follows these rules:

1. Reuse a partial file only from the current package-keyed staging directory and only when its actual length does not exceed the pinned file length.
2. Resume with an HTTP `Range` request and require a compatible `206` response. If the server returns a full `200`, truncate the partial file and restart from byte zero.
3. Stream bytes while updating progress and require the exact expected final length.
4. Keep normalization state in an adjacent marker. If normalization was interrupted, discard that partial file and marker and restart the download from byte zero before converting again.
5. Open both files through the native runtime and verify that the model has a decoder, the projector advertises vision support, and the embedded chat template can render the required image marker.
6. Atomically publish the complete package directory. Consumers never open staging files.
7. A native capability-load failure reports a safe error and does not publish an unusable package. Existing compatible packages, including legacy-named directories, are discovered through safe metadata, filenames, lengths, and capability checks.

## Module boundaries

### `pipeline-core`

The portable Kotlin module owns:

- OCR contracts, terminal states, errors, reports, and strict serialization;
- deterministic candidate consolidation, bubble association, reading order, and region identities;
- page and run artifact keys;
- crop-attempt descriptors and quality-signal evaluation;
- legal job, page, and region transitions;
- region-level checkpoint validation and atomic run publication.

It has no Android, bitmap, JNI, llama.cpp, or networking dependency.

### Android Kotlin layer

The Android application owns:

- model-package download, resume, verification, and progress;
- source bitmap reopening and visible-orientation handling;
- crop rendering for the portable crop descriptors;
- the `OcrEngine` and `OcrEngineFactory` interfaces;
- JNI lifecycle, foreground execution, notification, cancellation, and restart recovery;
- OCR preview rendering and project-level UI.

The runner depends on `OcrEngine`, not the concrete native class, so instrumentation tests use generated images and a deterministic fake engine.

### Native C++ layer

The app's native layer owns:

- a pinned llama.cpp and `mtmd` build for `arm64-v8a`;
- model and projector loading;
- chat-template rendering with one image and the `OCR:` instruction;
- RGB bitmap ingestion, multimodal tokenization, greedy token generation, UTF-8 assembly, and EOS handling;
- per-token probability capture before sampling;
- repetition detection, token-level cancellation, timings, and sanitized native error codes;
- immediate release of crop-specific bitmap, embedding, batch, sampler, and context objects after each attempt.

One engine instance owns one loaded model and projector. It processes one crop at a time. No Java or native layer may create concurrent OCR contexts in this slice.

## Candidate consolidation

Only accepted `TEXT_IN_BUBBLE` and `TEXT_FREE` regions are OCR inputs. `BUBBLE` regions provide association and crop context but never become OCR candidates by themselves.

Consolidation is deterministic and produces an `OcrCandidate` for each retained cluster:

1. Normalize boxes to visible-page pixels and reject a box that is non-finite or empty after clipping.
2. Within one detector class, cluster candidates when intersection-over-union is at least `0.75` or either box contains at least `0.90` of the smaller box.
3. Across `TEXT_IN_BUBBLE` and `TEXT_FREE`, treat regions as duplicate proposals when intersection covers at least `0.70` of the smaller box. Retain `TEXT_IN_BUBBLE` semantics and preserve every source region ID.
4. Select the representative geometry from the highest-confidence member; use the union only when no member contains every other cluster member.
5. Associate a text candidate with the bubble having the greatest intersection-over-text-area when that coverage is at least `0.50`. Ties resolve by bubble confidence and then stable region ID.
6. Never merge candidates solely because they are nearby. Separate non-overlapping text regions inside one bubble remain separate candidates.
7. Before inference, discard a `TEXT_FREE` candidate only when its confidence is below `0.50` and it is either narrower than `5%` of that page's actual width or within `1%` of a page edge. This gate never removes `TEXT_IN_BUBBLE` candidates.

The values above are schema-owned configuration and participate in artifact identity. They may be changed only by publishing a new OCR run key.

### Candidate identity

`ocrRegionId` is a structural child ID containing the persisted OCR page artifact key and a stable candidate index based on:

- page ID and detection page artifact key;
- the sorted source detection region IDs;
- consolidation schema version;
- resulting semantic source class.

Geometry, confidence, timestamps, and recognized text are excluded. The ID is persisted before region work starts. Crop or model changes create a new page/run lineage while the source detection-region lineage remains explicit.

## Reading order

The default source reading order is Japanese right-to-left, top-to-bottom. The portable sorter places regions in the same horizontal band when their vertical intersection covers at least `0.35` of the shorter region. It orders bands by their minimum top coordinate, orders members of one band by right edge descending and then top ascending, and uses deterministic region ID to break exact coordinate ties.

Each OCR candidate receives a zero-based `readingOrderRank`. The algorithm revision participates in the page artifact key. Translation may later add a semantic order override, but it must not silently rewrite this OCR order.

## Crop attempts

Every candidate has at most three ordered crop attempts:

1. `PADDED_TEXT`: expand the text box by `12%` of its width and height, with a minimum of four visible pixels on each side.
2. `TIGHT_TEXT`: expand by `4%`, used when the first output is empty, repeated, truncated, or low quality.
3. `CONTEXT_TEXT`: expand by `24%` but clip to the associated bubble when one exists; otherwise clip to the page. It is used only when the first two normalized outputs do not agree.

All boxes are clipped to visible-page bounds. The source is reopened for each retry so a failed bitmap or native attempt cannot reuse mutated image state. Crop images are transient and are not published as project artifacts.

## Native OCR contract

`OcrEngine.recognize` accepts encoded RGB crop bytes, visible width and height, a maximum token count, and a cooperative cancellation token. It returns:

- raw UTF-8 text and normalized text;
- generated token IDs and per-token probabilities;
- original and model-processed image dimensions;
- number of visual tokens and generated tokens;
- EOS, truncation, and repetition-stop flags;
- prompt-evaluation and generation durations.

Generation is deterministic: temperature is zero, sampling is greedy, repetition penalty is `1.2`, and generation stops on EOS, cancellation, pathological repetition, or `256` generated tokens.

Normalization trims surrounding whitespace, converts CRLF to LF, removes a single surrounding Markdown code fence when present, and applies Unicode NFC. It does not remove punctuation, prolonged sound marks, kana, Latin text, numbers, or line breaks inside the recognized content. Raw output is always retained beside normalized output.

## Quality evaluation and terminal states

PaddleOCR-VL does not provide a calibrated region confidence, so Masumi computes a quality record from:

- geometric mean generated-token probability;
- detector confidence;
- normalized edit similarity between crop attempts;
- Unicode script composition;
- empty output, repeated-unit, forced-truncation, invalid UTF-8, and abnormal-length signals.

The quality evaluator never rejects content merely because it contains only numbers, punctuation, Latin characters, or kana. A result is `RECOGNIZED` when it is non-empty, valid UTF-8, not pathologically repeated, not forcibly truncated, and either a second attempt agrees with normalized edit similarity of at least `0.90` or the primary attempt has geometric-mean token probability of at least `0.55`. The initial `0.55` value is schema-owned configuration and participates in artifact identity; later evidence may change it only by producing a new run key.

An uncertain non-empty best result is stored with state `NEEDS_FALLBACK`. A crop or inference failure after the allowed retry becomes `PRESERVED_SOURCE`. Neither state exposes recognized text to translation as trusted input. Empty detector proposals become `NO_TEXT_CONFIRMED` only when at least two crop attempts return empty output without a native error; otherwise they remain `NEEDS_FALLBACK`.

Bubble-associated candidates keep semantic status `REQUIRED_TEXT`. Free-text candidates keep `UNRESOLVED_FREE_TEXT` and protection policy `PRESERVE_UNTIL_CLASSIFIED`. This slice never labels free text as a sound effect based on text length or typography heuristics.

## Artifact identity and layout

An OCR page artifact key is an opaque safe ID allocated before page work. Its explicit dependency record contains:

- source page ID, recorded byte length, and detection page artifact key;
- OCR schema, consolidation, reading-order, crop, normalization, and quality-policy revisions;
- both model-file descriptors and package key;
- llama.cpp tag, ABI, native backend, and native build contract;
- prompt and generation configuration.

The run key is an opaque safe ID persisted with the ordered manifest entries, their detection page keys, OCR page keys, and declared OCR dependencies. Resume reuses it only when those explicit records still match; a changed dependency creates a new run.

Published output uses this layout:

```text
projects/<project-id>/
├── jobs/
│   └── <ocr-job-id>.json
└── artifacts/
    └── ocr/
        └── <ocr-run-key>/
            ├── artifact.json
            ├── pages/
            │   └── <page-id>/
            │       └── ocr.json
            ├── previews/
            │   └── <zero-padded-page-order>.png
            └── report.json
```

Incomplete output lives under `staging/ocr/<job-id>/<ocr-run-key>/`. Region checkpoints are stored below each page checkpoint. A page artifact becomes committed only after all its candidates have terminal states and its preview has been written. The complete run is published atomically after all manifest entries are terminal.

`ocr.json` stores page identity, the exact dependency record, candidate geometry and provenance, bubble association, reading rank, attempts, raw and normalized text, tokens, probabilities, quality signals, terminal state, and sanitized error. It contains no crop bitmap, source filename, absolute path, download URL, or raw native exception.

The preview overlays the immutable source with:

- green for `RECOGNIZED`;
- amber for `NEEDS_FALLBACK`;
- gray for `NO_TEXT_CONFIRMED`;
- red for `PRESERVED_SOURCE`;
- orange outline for `UNRESOLVED_FREE_TEXT` protection.

Labels contain terminal state, aggregate quality score, retry count, and shortened OCR region ID. The complete recognized text is shown in the app's page detail area rather than painted over the source preview.

## Execution and recovery

OCR runs in a foreground service and loads one native engine instance after verifying the model package. The normal job flow is:

```text
QUEUED
  -> DOWNLOADING_MODEL
  -> LOADING_MODEL
  -> RUNNING
       -> SUCCEEDED
       -> SUCCEEDED_WITH_PRESERVED_REGIONS

Any non-terminal state -> CANCELLED
Any non-terminal state -> FAILED
```

Each region follows:

```text
PENDING -> RUNNING -> RECOGNIZED
                   -> NEEDS_FALLBACK
                   -> NO_TEXT_CONFIRMED
                   -> PRESERVED_SOURCE
```

Before each attempt, the runner journals the region as `RUNNING`. A successful terminal region checkpoint is atomically published before the next region starts. On process recreation, a region left `RUNNING` returns to `PENDING`; validated terminal region checkpoints are reused. Duplicate source pages reuse page OCR work while retaining separate ordered previews.

Cancellation is checked before every page, region, crop attempt, image-tokenization step, and generated token. A cancelled attempt releases native resources, returns the current region to `PENDING`, retains earlier terminal checkpoints, and publishes no incomplete run artifact.

One crop inference failure closes and rebuilds the crop-specific context before the next crop strategy. A model or projector load failure is fatal. Repeated native failure for one region becomes `PRESERVED_SOURCE` and does not abort later regions. A native process crash is recovered through the durable journal; the interrupted region is the only region repeated.

## Performance boundaries

Model download and first model load are reported separately from page OCR. Steady-state processing is sequential and targets no more than 180 seconds per page on the representative acceptance device. Every crop-specific bitmap, visual embedding, batch, sampler, and llama context is released immediately after the attempt. The loaded model and projector remain alive for the job.

The initial native build uses the arm64 CPU backend. Thread count and image-pixel limits are fixed configuration values and participate in the runtime build contract. They may be tuned only from measured acceptance evidence. The slice does not add page concurrency or hold multiple llama contexts to chase throughput.

If a valid model cannot load within available memory or measured steady-state page latency remains above the target after bounded CPU and image-size tuning, the slice reports the measured blocker instead of silently replacing PaddleOCR-VL with a lower-quality engine.

## Error handling

The following are job-fatal:

- model or projector length or native capability mismatch;
- incompatible JNI or llama.cpp runtime revision;
- corrupt detection input or strict OCR schema;
- an unsafe, missing, or wrong-length source;
- unavailable workspace or failed final atomic publication;
- model load failure or unrecoverable native initialization failure.

The following are region-local and continue after the region reaches a protected terminal state:

- invalid crop after clipping;
- bitmap encoding or native image ingestion failure;
- prompt tokenization, context creation, decoding, UTF-8, repetition, or truncation failure;
- disagreement or low quality across all crop attempts.

Reports contain stable error codes and safe messages only. Credentials, signed URLs, absolute paths, hardware identifiers, raw native messages, and model response dumps are excluded.

## User interface

After a valid detection run is available, the project screen exposes `Run OCR`. Starting OCR shows model download or verification progress, current page and region, terminal region counts, elapsed time, and cancellation. The foreground notification shows the same concise progress.

After a terminal run, the user can navigate OCR previews in manifest order. The page detail shows recognized source text in reading order, quality state, and protected failures. No confirmation, checkbox, or manual correction is required to finish a run.

## Testing strategy

Implementation follows test-driven development.

### Host tests

Pure Kotlin tests cover:

- candidate overlap clustering, cross-class precedence, bubble association, and no proximity-only merge;
- deterministic right-to-left reading order;
- persisted OCR run/page identities and structural region identities;
- invalidation by every model, runtime, prompt, crop, and quality dependency;
- text normalization and crop-attempt selection;
- quality states for empty, repeated, truncated, agreeing, disagreeing, and low-probability output;
- legal job, page, region, cancellation, retry, and interruption transitions;
- region checkpoint validation, duplicate-source reuse, strict JSON, sanitization, and atomic run publication.

### Android tests

Instrumentation uses generated images and a fake `OcrEngine` to cover:

- visible-coordinate crop rendering and clipping;
- model download resume, full-response restart, interrupted-normalization recovery, length mismatch, capability mismatch, and atomic package publication;
- foreground progress, token-level cancellation propagation, and service restart recovery;
- reuse of committed regions without repeated fake inference;
- preview colors, page detail ordering, and output dimensions;
- unchanged source and detection bytes.

### Native tests

Native host or connected tests cover:

- JNI argument validation and sanitized errors;
- model and projector capability validation;
- chat template, image marker, and `OCR:` prompt rendering;
- RGB image ingestion, EOS, token probability capture, UTF-8 assembly, repetition stop, truncation, and cancellation;
- release of crop-specific resources after success, failure, and cancellation.

### Connected-device acceptance

An external representative chapter and its evaluation notes remain outside the repository. Acceptance demonstrates:

- every eligible candidate reaches a defined terminal state;
- source and detection lineage, paths, and recorded byte lengths remain unchanged;
- force-stopping and reopening the app repeats only the interrupted region;
- cancellation stops token generation and preserves earlier checkpoints;
- the app completes without native out-of-memory or uncontrolled concurrency;
- steady-state page latency remains at or below 180 seconds;
- Android OCR is compared with the same pinned model on the reference desktop runtime for region coverage, normalized text, and character similarity;
- every uncertain or failed region is retained in the terminal report.

## Public repository constraints

Committed source, tests, fixtures, logs, screenshots, plans, and documentation must not contain user, account, host, device, corpus, or evaluation identifiers. Tests use generated images and synthetic OCR outputs. Model binaries, evaluation pages, OCR dumps from evaluation pages, and device-specific measurements are never committed.

## Deferred work

This slice intentionally defers:

- a secondary OCR engine or remote fallback;
- semantic classification of unresolved free text;
- full-page spotting for missed detector regions;
- translation, glossary, and chapter-context handling;
- pixel masks, source-text removal, and inpainting;
- translated text layout, rendering, and final export.

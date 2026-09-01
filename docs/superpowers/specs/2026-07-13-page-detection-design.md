# Masumi Page Detection Design

Date: 2026-07-13

Status: Approved

## Purpose

This slice turns every immutable source page in an imported project into a versioned, inspectable detection artifact. It detects speech bubbles, text inside bubbles, and free text; preserves the detector's raw output; and creates annotated preview images for visual inspection.

The slice does not perform OCR, translation, classification of free text, image cleanup, typesetting, or export. Source pixels remain immutable. In particular, free text is protected as unresolved content so a later stage can distinguish narration from sound effects without losing either.

The slice is successful when an imported chapter can be analyzed unattended, every manifest entry reaches a terminal page state, valid completed work survives process recreation, and the application produces stable region JSON, previews, and a concise report without modifying any source object.

## Approaches considered

### 1. A pinned, compact local comic detector — selected

Run the INT8 small variant of `ogkalu/comic-text-and-bubble-detector` through ONNX Runtime on Android. The model emits separate bubble, text-in-bubble, and free-text candidates, which maps directly to this slice's required artifact contract.

This approach gives deterministic page geometry, no per-page API cost, bounded runtime, and a small initial model download. Keeping detection local also allows later OCR and translation experiments to reuse the same immutable regions without paying to detect the page again.

### 2. Port the complete desktop detection and OCR stack immediately

This could reproduce more of a mature desktop pipeline in one step, including segmentation and OCR-related models. It would also introduce several large models, multiple runtime adapters, and unclear failure attribution before the Android artifact and recovery contracts are proven.

The useful pieces of that stack remain candidates for later bubble-mask segmentation, OCR fallback, cleanup, and rendering stages. They are not needed to prove page detection.

### 3. Use a cloud vision model for page geometry

A vision model can jointly interpret unusual layouts, but its coordinates and response schema are less deterministic. It would add latency, cost, network failure modes, and provider-specific behavior to the earliest reusable stage. Cloud fallback remains an option after local recall can be measured from saved previews and reports.

## Model package

The first detector package is pinned to immutable upstream metadata:

| Field | Value |
| --- | --- |
| Repository | `ogkalu/comic-text-and-bubble-detector` |
| Upstream revision | `16e8a622f91fabc6b5b65c96d32d1183f8843546` |
| File | `detector-v4-s_int8.onnx` |
| Byte length | `11120765` |
| License | Apache-2.0 |
| ONNX opset | 18 |

The ONNX binary is downloaded on demand and is never committed to the repository. The Android application pins ONNX Runtime Android `1.27.0` for this slice. A later dependency upgrade changes the detector runtime revision and therefore invalidates the relevant cache keys.

The model store uses this layout:

```text
workspace/
└── models/
    └── <model-id>/
        └── <storage-revision>/
            ├── model.onnx
            └── package.json
```

`package.json` records the package schema version, model ID, storage revision, upstream repository and revision, original filename, exact byte length, license, ONNX signature, and acquisition time. It contains no download credentials or temporary URL query parameters.

### Download and publication

1. Download to a uniquely owned `*.part` file.
2. Stream the response while counting bytes.
3. Reject an unexpected final length.
4. Open the candidate with ONNX Runtime and validate the expected input and output signature.
5. Write `package.json` beside the candidate.
6. Atomically publish the complete model package directory.

A partial download is never considered installed. Existing valid packages are reused without a network request. Stale or incomplete temporary files are removed during recovery.

## Model contract and preprocessing

The pinned model has two inputs:

- `images`: float32 tensor `[N, 3, 640, 640]`;
- `orig_target_sizes`: int64 tensor `[N, 2]`, containing the visible page height and width.

It has three outputs, each with 300 queries per page:

- `labels`: int64 `[1, 300]`;
- `boxes`: float32 `[1, 300, 4]`;
- `scores`: float32 `[1, 300]`.

The class mapping is fixed by the model package:

- `0`: `BUBBLE`;
- `1`: `TEXT_IN_BUBBLE`;
- `2`: `TEXT_FREE`.

The page is decoded into a visible RGB raster. EXIF orientation is applied in memory, but the stored source object is not rewritten. All saved coordinates and previews use this visible raster coordinate system.

Preprocessing follows the pinned upstream configuration: bilinear resize directly to `640 x 640`, RGB channel order, rescale by `1 / 255`, no mean or standard-deviation normalization, and no padding. `orig_target_sizes` allows the exported boxes to be expressed in visible-page pixels.

The first acceptance threshold is `0.25` for every class. The threshold is intentionally recall-oriented. All 300 raw queries are retained regardless of threshold so model behavior can be diagnosed later.

For accepted candidates, boxes are normalized to left, top, right, bottom order and clipped to the page bounds. Non-finite boxes and boxes with no positive area after clipping are excluded from accepted candidates but remain present in the raw query list with a stable rejection reason.

## Artifact identity and reuse

Run and page artifact keys are persisted opaque safe IDs. New independent keys come from `IdSource`; child region IDs may be derived structurally from the persisted page artifact key and the model query coordinates described below.

### Page artifact key

`pageArtifactKey` is allocated and persisted before page work begins. A committed page is reusable only when its explicit dependency record matches the source page ID and recorded length, detection artifact schema, model repository and revision, runtime revision, preprocessing configuration, and threshold configuration.

### Run artifact key

`runArtifactKey` is allocated and persisted when a new detection job is created. Resume reuses that ID only when the job's ordered page lineage and every declared schema, model, preprocessing, and threshold dependency still match. A changed dependency creates a new run with new page keys.

### Region identity

Every accepted candidate receives a structural `regionId` derived from:

- `pageId`;
- `pageArtifactKey`;
- raw model query index;
- model class.

Geometry and confidence are deliberately excluded. Later geometry refinement, OCR, translation, or mask segmentation can update derived fields while retaining the original region identity. A class change creates a new identity because it changes downstream handling.

## Artifact layout

Detection output is published below the existing immutable project:

```text
projects/<project-id>/
├── jobs/
│   └── <job-id>.json
└── artifacts/
    └── detection/
        └── <run-artifact-key>/
            ├── artifact.json
            ├── pages/
            │   └── <page-id>/
            │       └── regions.json
            ├── previews/
            │   └── <zero-padded-page-order>.png
            └── report.json
```

While a run is incomplete, its files live under a job-owned checkpoint at `staging/detection/<job-id>/<run-artifact-key>/`. The job ID gives every task exclusive ownership, so cleanup can never remove another task's staging after a key collision. Consumers ignore this checkpoint area. After all manifest entries are terminal, the complete run directory is atomically moved to the published layout above.

`artifact.json` records the run schema, detector package reference, preprocessing and threshold configuration, creation time, and ordered manifest entries. Each entry references its page artifact key, terminal state, regions file, and preview file.

Each manifest page owns one `pages/<page-id>/regions.json`. Duplicate source bytes remain independent ordered pages and receive independent page artifacts and previews.

`regions.json` contains:

- schema version, page ID, recorded source length, and page artifact key;
- visible width, height, and applied orientation;
- exact detector package and configuration references;
- all 300 raw queries with query index, label, score, raw box, and validation result;
- accepted bubble candidates;
- accepted text regions;
- region IDs, source query indices, class, confidence, clipped boxes, and semantic status.

Bubble candidates and text regions remain separate. A missing or low-confidence bubble never vetoes a detected text region. `TEXT_FREE` is saved with semantic status `UNRESOLVED_FREE_TEXT` and protection policy `PRESERVE_UNTIL_CLASSIFIED`. This slice does not decide whether it is narration or a sound effect.

The preview is a derived PNG. It renders the visible source raster with overlays only:

- blue: bubble candidate;
- green: text inside a bubble;
- orange: unresolved free text;
- label text: class, confidence, and shortened region ID.

No preview operation writes to the source path or changes the source manifest.

## Component boundaries

### `pipeline-core`

The pure Kotlin module owns portable contracts and deterministic behavior:

- model package and detector configuration records;
- opaque run and page artifact ID allocation;
- stable region ID generation;
- raw query, accepted region, page result, job journal, and report schemas;
- box normalization, finite-value checks, clipping, and rejection reasons;
- job and page state transitions;
- atomic artifact publication rules;
- cache validation against exact dependencies.

It depends on no Android or ONNX Runtime classes. Tests can feed synthetic query arrays and failure-injected storage operations without loading a model.

### `app`

The Android module owns platform and inference behavior:

- model package download, verification, and local storage;
- ONNX Runtime session creation and signature validation;
- Android image decoding, in-memory orientation, and tensor preprocessing;
- adapting model tensors into the portable raw-query contract;
- preview rendering;
- foreground execution, notification, cancellation, and restart recovery;
- project-level progress and preview navigation UI.

The ONNX adapter is behind a narrow detector interface. The job coordinator depends on that interface instead of constructing a session directly, which allows deterministic Android tests with a fake detector.

## Execution and recovery

Detection runs as a foreground task with at most one page inference in flight. The first slice uses one reusable ONNX Runtime session and sequential page processing. Device-specific thread tuning and multi-page inference concurrency are deliberately deferred until measured evidence justifies them.

The normal job state flow is:

```text
QUEUED
  -> DOWNLOADING_MODEL
  -> RUNNING
       -> SUCCEEDED
       -> SUCCEEDED_WITH_PRESERVED_PAGES

Any non-terminal state -> CANCELLED
Any non-terminal state -> FAILED
```

Each ordered page entry has one of these durable states:

```text
PENDING -> RUNNING -> COMMITTED
                   -> PRESERVED_SOURCE
                   -> PENDING on interrupted recovery
```

The coordinator follows these rules:

1. Persist the job description before starting model acquisition.
2. Validate or acquire the pinned model package.
3. Allocate or restore page artifact keys and reuse only artifacts whose complete explicit dependency records validate.
4. Before processing a page, atomically journal it as `RUNNING`.
5. Write regions and preview into a page-owned temporary directory inside the job checkpoint.
6. Flush and atomically promote the completed page data to a durable checkpoint; this is not yet a public run artifact.
7. Atomically journal the page as `COMMITTED` before advancing.
8. Write `artifact.json` and `report.json` only after all ordered entries are terminal.
9. Atomically move the complete run checkpoint to `artifacts/detection/<run-artifact-key>`.

On process recreation, any non-terminal job is returned to resumable work. A partial model download is removed and reacquired. Any page left in `RUNNING` becomes `PENDING`; only incomplete page staging owned by that job is removed. Already validated committed checkpoints are not inferred again.

Cancellation is cooperative at page boundaries. The current page may finish and checkpoint, but no new page starts after cancellation is observed. Completed checkpoints are retained and remain reusable when that job resumes. The job becomes `CANCELLED`, and its job record states exactly which pages committed before cancellation. A cancelled job does not publish an incomplete run artifact or a final `report.json`.

## Error handling

The following are job-fatal because further results cannot be trusted or published safely:

- model download length mismatch;
- incompatible ONNX signature;
- corrupt or unsupported artifact schema;
- unavailable project workspace or atomic publication failure;
- an unsafe, missing, or wrong-length source relative to the immutable manifest.

A page decode, inference, tensor-conversion, or preview-generation failure is retried once. Before retrying an inference failure, the current ONNX session is closed and rebuilt. Before retrying a decode or preview failure, the source is reopened and temporary page output is cleared.

If the second attempt fails, that page becomes `PRESERVED_SOURCE`; the coordinator records a sanitized error code and continues with the remaining pages. No empty region file or misleading preview is published for that page. A run containing one or more preserved pages ends as `SUCCEEDED_WITH_PRESERVED_PAGES` and the report lists them.

The phrase `PRESERVED_SOURCE` means later stages must use the unchanged original page and must not infer that the page contains no text. It is an explicit failure state, not a successful empty detection.

Errors and reports exclude provider credentials, download URL query parameters, document URIs, absolute filesystem paths, hardware details, and raw exception text.

## User interface

After import, the project screen shows the manifest page count and an `Analyze pages` action. Starting analysis presents:

- model package download and verification progress when needed;
- completed, preserved, and total page counts;
- the currently processed page order;
- a foreground notification with the same summary;
- a cancellation action.

After a terminal run, the screen provides previous and next navigation through the annotated previews, the three-color legend, region confidence labels, and a concise report summary. A preserved page shows the original image with a clear failure marker instead of a fabricated empty overlay.

This is a diagnostic interface for proving recall and artifact correctness. Editing boxes, accepting regions manually, OCR text, translation text, and production reading UI are outside this slice.

## Testing strategy

Implementation follows test-driven development.

### Host tests

Pure Kotlin tests cover:

- opaque run/page key persistence and structural region ID generation;
- cache invalidation for every declared dependency;
- stable region IDs independent of geometry changes;
- box normalization, clipping, non-finite rejection, and zero-area rejection;
- preservation of all 300 raw queries;
- separation of bubble candidates and text regions;
- `TEXT_FREE` protection semantics;
- legal and illegal job and page state transitions;
- retry, preserved-page continuation, cancellation, and resume decisions;
- atomic page and run publication under injected failures;
- JSON round trips and public-data sanitization.

### Android tests

Android tests use a fake detector interface and synthetic images to cover:

- visible-raster orientation and coordinate mapping;
- tensor shape, RGB order, and rescaling;
- preview colors and output dimensions;
- foreground coordinator progress and restart recovery;
- model package rejection for bad length or signature;
- cleanup of partial model and page staging.

Automated tests do not depend on downloading the real model. A separate runtime smoke check validates the pinned model signature and executes one representative page when the model package is available.

### Connected-device acceptance

Acceptance on a representative imported chapter proves that:

- every ordered manifest entry reaches `COMMITTED` or `PRESERVED_SOURCE`;
- every source still resolves within the project root with its recorded byte length;
- every committed page has valid region JSON and a viewable annotated preview;
- process termination during analysis resumes without repeating validated pages;
- cancellation stops before another page starts and retains completed work;
- the app does not crash or start uncontrolled concurrent inference;
- the report contains duration, class counts, confidence distribution, retries, preserved pages, and terminal status;
- dialogue and narration candidates are visible at recall-oriented thresholds, while unresolved free text remains preserved for later classification.

Development installation reuses the existing debug package. Routine verification uses build, replacement install, and direct instrumentation invocation where needed; it avoids workflows that uninstall the package and repeatedly trigger platform install confirmation.

## Public repository constraints

Committed source, tests, fixtures, logs, screenshots, and documentation must not contain:

- user, account, device, or host identifiers;
- evaluation corpus names, page counts, paths, images, or evaluation notes;
- API keys, bearer tokens, signed URLs, or provider response bodies;
- absolute local filesystem paths or document URIs.

Tests use generated images and synthetic detector outputs. Private quality references and device-specific measurements stay outside the repository.

## Deferred work

This slice intentionally defers:

- narration-versus-sound-effect classification;
- OCR and OCR fallback;
- cross-region reading order;
- speech-bubble mask segmentation;
- cloud detection fallback;
- translation and terminology handling;
- background cleanup and inpainting;
- font selection, text layout, and renderer integration;
- quality scoring and automatic retry based on visual quality;
- flattened image, archive, or PDF export.

Those stages consume the stable detection artifacts defined here. None may mutate the imported source object or reinterpret `PRESERVED_SOURCE` as an empty successful result.

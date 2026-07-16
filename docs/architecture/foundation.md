# Foundation architecture

## Scope

The foundation slice imports an immutable manga chapter, analyzes every page with a pinned local comic detector, and recognizes the resulting text candidates with pinned PaddleOCR-VL 1.6 model files. Detection and OCR each produce strict JSON, derived previews, resumable job state, and a terminal report. Translation, cleanup, typesetting, and final export are not implemented yet.

The design has three goals:

1. Every later stage reads stable, hash-verified sources and versioned detection/OCR artifacts.
2. Cancellation, process loss, or one bad region never requires reimporting or repeating committed work.
3. Source images, credentials, filesystem details, and raw exception text never enter public artifacts or status broadcasts.

## Module boundary

`app` owns Android-specific behavior:

- system document-tree selection and persisted read permission;
- document URI adaptation into reopenable streams;
- bitmap decoding and in-memory EXIF orientation;
- exact RGB/CHW detector tensor preparation;
- ONNX Runtime session validation and inference;
- revision-pinned HTTPS model acquisition;
- resumable acquisition and capability validation of the two-file OCR model package;
- source crop rendering and an arm64 llama.cpp `mtmd` JNI runtime;
- foreground execution, cancellation, notifications, and package-scoped status broadcasts;
- progress display, safe preview navigation, and recognized-text details.

`pipeline-core` owns portable behavior:

- media selection, natural ordering, streaming copy, and SHA-256 hashing;
- strict import, detection, and OCR JSON contracts;
- deterministic detection/OCR page, run, candidate, and region identities;
- raw-query validation, thresholding, clipping, and class separation;
- OCR candidate consolidation, Japanese reading order, crop policy, normalization, and quality decisions;
- the strict OCR-to-translation input boundary, translation policy identity, and structured model-response contracts;
- legal job/page/region transitions, retry, cancellation, and interruption recovery;
- model-package source/installed length and hash checks, deterministic GGUF normalization, signature checks, and metadata checks;
- job journals, region/page checkpoints, reports, and atomic publication.

No Android class is referenced by `pipeline-core`.

## Import flow

1. Enumerate only direct children of the selected folder.
2. Reject directories, hidden entries, and unsupported media.
3. Sort accepted pages naturally by display name.
4. Copy each page into staging while computing SHA-256.
5. Reuse one stored source object when multiple entries contain identical bytes.
6. Write the manifest and import reports.
7. Atomically move the complete project into the visible project collection.

If a source read or storage operation fails, staging is removed and no project directory is published. A sanitized failure report is retained when storage remains available.

## Detection flow

1. Strictly read the manifest, validate contiguous order, and recompute every unique source digest.
2. Derive each page artifact key from source SHA, schema, pinned model, runtime, preprocessing, and thresholds; derive the run key from ordered page keys.
3. Reuse a complete published run, or recover a compatible cancelled/interrupted job.
4. Acquire the model through a job-owned staging directory and verify byte length, SHA-256, and tensor signature before publication.
5. Process unique source objects sequentially. Duplicate manifest entries share inference and region JSON but receive separate ordered previews.
6. Journal `RUNNING`, decode and orient in memory, infer exactly 300 queries, post-process, render a derived preview, checkpoint files, then journal `COMMITTED`.
7. On a page-processing failure, close and rebuild the detector once. A second failure becomes `PRESERVED_SOURCE`; the run continues.
8. Honour cancellation only at a page boundary so committed checkpoints remain valid.
9. Publish the complete run directory atomically before writing the terminal job state.

Source integrity, model-package, checkpoint-write, and final-publication failures are fatal to the run. Page decode, inference, output, and preview failures use the one-retry preservation policy.

## OCR flow

1. Strictly load the current published detection run and include its identity in every OCR cache key.
2. Consolidate overlapping text proposals without proximity-only merging, retain their provenance, associate dialogue-box context, and assign deterministic reading order. Before inference, discard only low-confidence free-text candidates that are implausibly narrow for that page or touch a page edge; in-box text is never removed by this gate.
3. Acquire the pinned language model and multimodal projector with resumable HTTP ranges. Verify the canonical BF16 digests, deterministically normalize both files to F16 in unpublished staging, verify the installed digests, then validate the pair through the CPU native path before publication.
4. Prefer one Vulkan engine for the job and retry initialization once with CPU when no usable accelerator can open. Process unique source pages and regions sequentially; duplicate page entries reuse OCR work while retaining ordered previews.
5. Render padded, tight, and contextual crops at their actual dimensions. The projector selects a crop-adaptive workload within the pinned 64–2048 visual-token range. Record raw and normalized text, actual backend, token probabilities, dimensions, stop flags, timings, and sanitized errors for every attempt.
6. Accept a result only when the quality policy has sufficient token probability or agreement between attempts. Low-confidence free-text proposals also require Han, Hiragana, or Katakana before agreement can accept them, so repeated digit/symbol hallucinations remain preserved. Confirmed empty regions are explicit; uncertain and failed regions retain the source artwork.
7. Atomically checkpoint each terminal region before starting the next one, then commit page JSON and preview only after every candidate is terminal.
8. Cancellation releases the active native inference and retains earlier checkpoints. Process loss returns only the interrupted region to pending.
9. Publish the complete OCR run directory atomically before reporting success or success-with-preserved-regions.

The pinned OCR package is about 1.82 GB combined. It is downloaded on first use, normalized in staging, remains in app-private storage, and is not included in the APK or repository. The native runtime contains arm64 Vulkan and CPU backends and intentionally runs one crop at a time to bound memory use.

## Project artifacts

```text
workspace/
├── models/
│   ├── <detector-package>/<sha256>/
│   │   ├── model.onnx
│   │   └── package.json
│   └── <ocr-package>/<package-sha256>/
│       ├── PaddleOCR-VL-1.6-GGUF.gguf
│       ├── PaddleOCR-VL-1.6-GGUF-mmproj.gguf
│       └── package.json
├── projects/
│   └── <project-id>/
│       ├── manifest.json
│       ├── sources/
│       │   └── <sha256>.<extension>
│       ├── reports/
│       │   ├── <import-job-id>.json
│       │   └── <import-job-id>.txt
│       ├── jobs/
│       │   └── <detection-or-ocr-job-id>.json
│       ├── staging/
│       │   ├── detection/<detection-job-id>/<run-key>/
│       │   └── ocr/<ocr-job-id>/<run-key>/
│       └── artifacts/
│           ├── detection/<run-key>/
│           │   ├── artifact.json
│           │   ├── report.json
│           │   ├── pages/<page-id>/regions.json
│           │   └── previews/<order>.png
│           └── ocr/<run-key>/
│               ├── artifact.json
│               ├── report.json
│               ├── pages/<page-id>/ocr.json
│               └── previews/<order>.png
├── staging/
└── failed-reports/
```

All paths stored in JSON are project-relative. The source manifest records the original display name for ordering/audit purposes, but reports and broadcasts contain no absolute paths, provider credentials, model URLs, hardware information, or raw exception text.

## Detection semantics

- Class `0` is a dialogue-box candidate and renders blue.
- Class `1` is text inside a box and renders green.
- Class `2` is unresolved free text and renders orange.
- Free text is explicitly protected until a later classifier decides whether it is dialogue, narration, or sound effect.
- The current default confidence threshold is `0.25` for every class.
- Accepted regions are never merged across detector classes.
- Every raw query remains in the page artifact with its validation outcome.

## OCR semantics

- Only accepted in-box text and unresolved free-text regions become OCR candidates; dialogue boxes provide crop context but are not recognized directly.
- Same-page duplicate proposals may consolidate only through explicit overlap/containment thresholds. Source region IDs remain attached to the OCR candidate.
- The free-text prefilter uses fractions of each page's actual width rather than fixed pixels, so mixed page dimensions do not change its meaning.
- Each attempt records enough bounded diagnostic data to reproduce the quality decision without storing crop images or absolute paths.
- `RECOGNIZED` publishes the selected normalized text; `NO_TEXT_CONFIRMED` records a deliberate empty result.
- `NEEDS_FALLBACK` and `PRESERVED_SOURCE` are successful protective outcomes: downstream image work must retain the corresponding source pixels.
- A successful run may therefore finish with protected regions and never requires interactive approval.

## Translation foundation

- Translation reads only terminal OCR page artifacts and never mutates them.
- Trusted recognized regions become stable ID-addressed items in OCR reading order; uncertain OCR remains protected and confirmed-empty regions are omitted.
- In-box text is a mandatory dialogue candidate. Free text is explicitly classified as narration, sound effect, or other text before policy is applied.
- The default policy translates dialogue and narration, preserves sound effects, and completes without manual approval.
- Structured responses are reconciled by stable ID. A missing or invalid item preserves its source pixels without discarding valid sibling results.
- Chapter windows are greedily filled under a versioned estimated-token budget, carry only bounded preceding context, and never truncate one oversized source item.
- Prompt context IDs are read-only. Only IDs from the current item array may appear in a response, exactly once each.
- Endpoint URLs and credentials are runtime-only settings and never enter project artifacts, reports, logs, or cache identity.

## Invariants

- Imported source objects are immutable and reverified before detection.
- A visible imported project and a visible detection run are each complete atomic publications.
- Repeated bytes may share source and detection work while remaining separate ordered pages.
- A committed page has validated region JSON and one preview for every referenced order.
- Cancellation happens only between pages; interruption recovery discards only the page that was running.
- A preserved page retains its source and stable error code and never fabricates regions.
- Unknown JSON fields are rejected so schema drift is explicit.
- Model identity, runtime revision, preprocessing, and thresholds participate in cache identity.
- OCR identity also includes the detection dependency, model/projector package, native runtime build contract, consolidation, reading-order, crop, normalization, quality, and generation policy.
- A committed OCR region has a validated terminal checkpoint; a committed OCR page has validated page JSON and ordered previews.
- At most one OCR engine and one crop inference are active. Cancellation and recovery never discard earlier terminal regions.
- Per-page detection and OCR always use each source page and crop's real dimensions; a later webtoon reading mode cannot alter OCR geometry or cache identity.
- Unknown OCR JSON fields are rejected, and all stored paths remain inside the project or model-package roots.

## Next slices

Structured translation is now the active slice. Provider execution, semantic response validation, glossary/chapter windows, resumable translation artifacts, and usage reporting come next. Artwork cleanup, typesetting, final visual quality checks, and flattened image export remain independent, reportable stages so each can be retried without mutating source pages or repeating valid earlier work.

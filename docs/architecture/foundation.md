# Foundation architecture

## Scope

The foundation slice imports an immutable manga chapter, analyzes every page with a pinned local comic detector, recognizes the resulting text candidates with pinned PaddleOCR-VL 1.6 model files, translates trusted Japanese text through an OpenAI-compatible provider, removes source glyphs from accepted translation regions, lays accepted Chinese text onto flattened pages, and exports the complete ordered page set to a user-selected folder. Every runtime stage has strict JSON, resumable job state, and a terminal report. The terminal runtime edge is `TYPESETTING -> EXPORT`; there is no on-device visual-quality audit or automatic quality-directed repair stage.

The design has three goals:

1. Import hashes source bytes once for immutable identity and deduplication; later stages use bounded source preflight and exact versioned artifact lineage instead of repeated whole-file hashing.
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
- the cancellable single-attempt OpenAI-compatible translation provider, safe error mapping, and usage parsing;
- conservative adaptive glyph masking, bubble fill, and free-text boundary inpainting;
- horizontal and vertical Chinese layout, deterministic maximum-readable-size fitting, and adaptive free-text contrast;
- Android document-tree write permission, staged generation publication, one final-file read-back for each new output, and exact filename-set verification at directory promotion;
- progress display, safe preview navigation, and recognized-text details.

`pipeline-core` owns portable behavior:

- media selection, natural ordering, streaming copy, and SHA-256 hashing;
- strict import, detection, and OCR JSON contracts;
- deterministic detection/OCR page, run, candidate, and region identities;
- raw-query validation, thresholding, clipping, and class separation;
- OCR candidate consolidation, Japanese reading order, crop policy, normalization, and quality decisions;
- the strict OCR-to-translation input boundary, translation policy identity, and structured model-response contracts;
- cleanup, typesetting, and folder-export dependency, policy, job/report, identity, and recovery contracts;
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

## Runtime validation and hashing boundary

- Import is the only stage that reads every source byte to establish its SHA-256 identity. The digest remains part of immutable page and cache identities.
- Later source consumers validate the project-relative path, root containment, regular-file existence, recorded byte length, successful image decode when pixels are needed, and the exact page/dependency lineage. They do not recompute the source SHA-256 during ordinary processing.
- Ordinary reads of locally and atomically published artifacts validate strict metadata, safe relative paths, file presence, and dependency lineage without rehashing image bytes. Creation-time digests may remain recorded in the contracts for identity and diagnostics.
- Full-content verification remains where it protects a distinct trust boundary: downloaded model packages retain length and SHA-256 checks, and each newly written SAF output receives one final-file read-back before its page checkpoint is committed.

## Detection flow

1. Strictly read the manifest, validate contiguous order and page lineage, and preflight each unique source by safe relative path, regular-file existence, and recorded byte length without recomputing its digest.
2. Derive each page artifact key from source SHA, schema, pinned model, runtime, preprocessing, and thresholds; derive the run key from ordered page keys.
3. Reuse a complete published run, or recover a compatible cancelled/interrupted job.
4. Acquire the model through a job-owned staging directory and verify byte length, SHA-256, and tensor signature before publication.
5. Process unique source objects sequentially. Duplicate manifest entries share inference and region JSON but receive separate ordered previews.
6. Journal `RUNNING`, decode and orient in memory, infer exactly 300 queries, post-process, render a derived preview, checkpoint files, then journal `COMMITTED`.
7. On a page-processing failure, close and rebuild the detector once. A second failure becomes `PRESERVED_SOURCE`; the run continues.
8. Honour cancellation only at a page boundary so committed checkpoints remain valid.
9. Publish the complete run directory atomically before writing the terminal job state.

Source-preflight, model-package, checkpoint-write, and final-publication failures are fatal to the run. Page decode, inference, output, and preview failures use the one-retry preservation policy.

## OCR flow

1. Strictly load the current published detection run and include its identity in every OCR cache key.
2. Consolidate overlapping text proposals without proximity-only merging, retain their provenance, associate dialogue-box context, and assign deterministic reading order. Before inference, discard only low-confidence free-text candidates that are implausibly narrow for that page or touch a page edge; in-box text is never removed by this gate.
3. Acquire the pinned language model and multimodal projector with resumable HTTP ranges. Verify the canonical BF16 digests, deterministically normalize both files to F16 in unpublished staging, verify the installed digests, then validate the pair through the CPU native path before publication.
4. Prefer one Vulkan engine for the job and retry initialization with CPU when no usable accelerator can open. If the Vulkan device is lost during inference, contain the native exception, finish the same crop on CPU, record a bounded cooldown marker, and probe Vulkan again after that cooldown instead of pinning the whole job to CPU. Process unique source pages and regions sequentially; duplicate page entries reuse OCR work while retaining ordered previews.
5. Render padded, tight, and contextual crops at their actual dimensions. The projector selects a crop-adaptive workload within the pinned 64–2048 visual-token range. Record raw and normalized text, actual backend, token probabilities, dimensions, stop flags, timings, and sanitized errors for every attempt.
6. Accept a result only when the quality policy has sufficient token probability or agreement between attempts. Low-confidence free-text proposals also require Han, Hiragana, or Katakana before agreement can accept them, so repeated digit/symbol hallucinations remain preserved. Confirmed empty regions are explicit; uncertain and failed regions retain the source artwork.
7. Atomically checkpoint each terminal region before starting the next one, then commit page JSON and preview only after every candidate is terminal.
8. Cancellation releases the active native inference and retains earlier checkpoints. Process loss returns only the interrupted region to pending.
9. Publish the complete OCR run directory atomically before reporting success or success-with-preserved-regions.

The pinned OCR package is about 1.82 GB combined. It is downloaded on first use, normalized in staging, remains in app-private storage, and is not included in the APK or repository. The native runtime contains arm64 Vulkan and CPU backends and intentionally runs one crop at a time to bound memory use.

## Cleanup flow

1. Load the latest published translation run and its exact published OCR dependency; validate every ordered page's lineage and bounded source preflight before decoding, without recomputing source digests.
2. Join accepted translation items to OCR geometry by stable region ID. Preserved translations and protected OCR regions are never cleanup targets.
3. Estimate a local background from each target perimeter, select high-contrast glyph pixels, dilate small gaps, and reject empty or implausibly large masks.
4. Fill in-bubble glyph masks with the local background. Repair translated free-text masks by propagating colors inward from their boundary.
5. Atomically commit one cleaned PNG and strict page JSON before advancing the job journal. A page failure preserves the complete source page and continues.
6. Recover cancellation or process loss at the active page only, then atomically publish the cleaned-page run and report.

## Typesetting flow

1. Load the latest cleanup run compatible with the current typesetting policy, then resolve its exact translation and OCR dependencies.
2. Join accepted translation text, OCR geometry, bubble associations, and cleanup outcomes by stable region ID. Protected or uncleaned regions remain original artwork.
3. Use the associated bubble bounds for dialogue and a modestly expanded text box for translated free text. All geometry is computed from each page's real dimensions.
4. Choose centered horizontal layout for wide regions and right-to-left vertical columns for tall regions, including versioned vertical punctuation substitution.
5. Binary-search the largest font size that fits the region. If no result reaches the absolute readability floor, preserve the cleaned pixels instead of publishing microscopic text.
6. Render dialogue in the cleaned bubble and free text with adaptive black-or-white glyphs plus a contrasting outline, clipped to the selected layout box.
7. Atomically commit one flattened PNG and strict page JSON, recover only the active page after cancellation or process loss, then publish the complete run and report.

## Folder export flow

1. Select a writable Android document tree. Export writes into a job-scoped staging directory and publishes that complete directory as a named output generation.
2. Bind the job directly to the exact published typesetting run and a one-way destination key. Resolve its cleanup dependency only for per-page fallback; no runtime quality artifact gates export. The private job journal retains the URI only for recovery.
3. Name pages by manifest order with zero-padded image filenames. Prefer the flattened page, then a committed cleanup fallback, then an encoded immutable source fallback.
4. Ordinary local artifact resolution validates metadata, safe paths, presence, decode where required, and exact lineage without rehashing the stored artifact. The export job hashes the output bytes it is about to publish so an external copy can be checked or resumed safely.
5. Reuse an existing destination page only after its recorded length and SHA-256 match. For a newly written page, promote or create the final document and read that final file back exactly once to verify length and SHA-256 before checkpointing it.
6. Before promoting the staging directory, compare the complete set of image filenames with the expected set. Directory promotion does not reread or rehash page contents.
7. Recover cancellation or process loss at the active page, revalidate earlier external checkpoints as needed, then write a sanitized internal report with source-kind and reuse counts. Best-effort pruning runs only after the published export job and report are durable.

## Runtime quality boundary

Renderer correctness is enforced by focused tests and strict typesetting contracts, not by a production page-audit stage. The app does not compare every cleaned page with its flattened page at runtime, block export on a visual-quality report, or schedule automatic quality-directed re-typesetting. The earlier automatic-quality and quality-directed-repair documents are retained only as superseded design history.

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
│       │   ├── <import-job-id>.txt
│       │   └── export/<export-job-id>.json
│       ├── jobs/
│       │   ├── <detection-ocr-translation-cleanup-or-typesetting-job-id>.json
│       │   └── export/<export-job-id>.json
│       ├── staging/
│       │   ├── detection/<detection-job-id>/<run-key>/
│       │   ├── ocr/<ocr-job-id>/<run-key>/
│       │   ├── translation/<translation-job-id>/<run-key>/
│       │   ├── cleanup/<cleanup-job-id>/<run-key>/
│       │   └── typesetting/<typesetting-job-id>/<run-key>/
│       └── artifacts/
│           ├── detection/<run-key>/
│           │   ├── artifact.json
│           │   ├── report.json
│           │   ├── pages/<page-id>/regions.json
│           │   └── previews/<order>.png
│           ├── ocr/<run-key>/
│           │   ├── artifact.json
│           │   ├── report.json
│           │   ├── pages/<page-id>/ocr.json
│           │   └── previews/<order>.png
│           ├── translation/<run-key>/
│           │   ├── artifact.json
│           │   ├── report.json
│           │   ├── glossary.json
│           │   ├── windows/<window>.json
│           │   └── pages/<order>-<page-id>/translation.json
│           ├── cleanup/<run-key>/
│           │   ├── artifact.json
│           │   ├── report.json
│           │   └── pages/<order>-<page-id>/
│           │       ├── cleanup.json
│           │       └── cleaned.png
│           └── typesetting/<run-key>/
│               ├── artifact.json
│               ├── report.json
│               └── pages/<order>-<page-id>/
│                   ├── typesetting.json
│                   └── flattened.png
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
- Chapter windows are greedily filled under a versioned estimated-token budget and a 12-item response bound, carry only bounded preceding context, and never truncate one oversized source item.
- Prompt context IDs are read-only. Only IDs from the current item array may appear in a response, exactly once each.
- Endpoint URLs and credentials are runtime-only settings and never enter project artifacts, reports, logs, or cache identity.
- Android stores the endpoint, key, and model in application-private preferences and runs translation in its own foreground service with structured progress, cancellation, and automatic interrupted-job recovery.
- Detection, OCR, translation, cleanup, typesetting, and export foreground services hold a bounded partial wake lock only while work is active. Before new work, the UI requests Android's battery-optimization exemption because some OEM schedulers disable ordinary wake locks for non-exempt apps. Every service releases its lock on terminal completion, cancellation teardown, or destruction.
- Translation network calls use bounded OkHttp timeouts and exactly one transport attempt. Network, timeout, HTTP, and malformed-response failures expose only safe metadata and stop the stage immediately.
- Missing, duplicate, invalid-role, or blank required responses fail without publication and do not trigger batch salvage. Only Japanese-bearing or source-echo items receive one isolated semantic quality repair; a second semantically invalid result remains protected while valid siblings publish normally.
- Translation windows are checkpointed before their job journal advances. Process recovery retains the continuous trusted terminal prefix and invalidates the active or pending glossary-dependent suffix. Legacy provider-failed checkpoints are treated as untrusted and are never published.
- A published translation run atomically contains strict page artifacts, its final normalized glossary, dependency record, and terminal usage/protection report.

## Invariants

- Imported source objects are immutable and receive their full-content SHA-256 identity during import. Downstream source preflight validates safe path, existence, recorded length, decode when consumed, and lineage without routine rehashing.
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
- Cleanup changes pixels only inside an accepted glyph mask for a region with a valid translation; protected or unsafe regions retain source pixels.
- Cleanup releases the decoded source immediately after creating its mutable page copy, bounds inpainting frontier allocations with primitive buffers, and runs with the Android large-image heap to avoid high-resolution page OOM fallback.
- Cleanup identity includes the exact translation run and every policy field. A committed cleanup page has validated JSON and an atomically published PNG with a creation-time digest; ordinary reads do not recompute it.
- Cleanup cancellation and process recovery discard only the active page and never repeat OCR or translation.
- Typesetting changes only regions with accepted translation and committed cleanup. A layout below the readability floor preserves its cleaned pixels.
- Typesetting identity includes the exact cleanup, translation, and OCR dependencies plus every layout policy field. A committed page has validated JSON and an atomically published flattened image with a creation-time digest; ordinary reads do not recompute it.
- Typesetting cancellation and process recovery discard only the active page and never repeat cleanup or any earlier stage.
- Folder export follows typesetting directly and publishes exactly one image per manifest page. Its report contains a destination digest, never the document-tree URI.
- Each newly written SAF final file receives exactly one content read-back. Publishing the complete generation validates the exact filename set without an additional content pass.
- Export may replace deterministic page names inside its staging generation and prunes older managed outputs only after the new generation and its durable success record are complete; unrelated destination documents are never deleted.
- Export cancellation and recovery revalidate committed external outputs and never repeat any localization stage.

## Next slices

The full import-to-folder-export path is represented by independently resumable stages and ends with the direct `TYPESETTING -> EXPORT` edge. Runtime visual auditing and automatic quality repair have been retired; future quality work should stay in focused tests unless a separately justified production-stage design is approved.

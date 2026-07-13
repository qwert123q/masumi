# Foundation architecture

## Scope

The foundation slice imports an immutable manga chapter and analyzes every page with a pinned local comic detector. It produces strict region JSON, derived annotated previews, resumable job state, and a terminal report. It does not yet perform OCR, translation, cleanup, typesetting, or final export.

The design has three goals:

1. Every later stage reads stable, hash-verified sources and versioned detection artifacts.
2. Cancellation, process loss, or one bad page never requires reimporting or repeating committed pages.
3. Source images, credentials, filesystem details, and raw exception text never enter public artifacts or status broadcasts.

## Module boundary

`app` owns Android-specific behavior:

- system document-tree selection and persisted read permission;
- document URI adaptation into reopenable streams;
- bitmap decoding and in-memory EXIF orientation;
- exact RGB/CHW detector tensor preparation;
- ONNX Runtime session validation and inference;
- revision-pinned HTTPS model acquisition;
- foreground execution, cancellation, notifications, and package-scoped status broadcasts;
- progress display and safe preview navigation.

`pipeline-core` owns portable behavior:

- media selection, natural ordering, streaming copy, and SHA-256 hashing;
- strict import and detection JSON contracts;
- deterministic page, run, and region identities;
- raw-query validation, thresholding, clipping, and class separation;
- legal job/page transitions, retry, cancellation, and interruption recovery;
- model-package length, hash, signature, and metadata checks;
- job journals, page checkpoints, reports, and atomic publication.

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

## Project artifacts

```text
workspace/
├── models/
│   └── <model-package>/<sha256>/
│       ├── model.onnx
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
│       │   └── <detection-job-id>.json
│       ├── staging/detection/
│       │   └── <detection-job-id>/<run-key>/
│       └── artifacts/detection/
│           └── <run-key>/
│               ├── artifact.json
│               ├── report.json
│               ├── pages/<page-id>/regions.json
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

## Invariants

- Imported source objects are immutable and reverified before detection.
- A visible imported project and a visible detection run are each complete atomic publications.
- Repeated bytes may share source and detection work while remaining separate ordered pages.
- A committed page has validated region JSON and one preview for every referenced order.
- Cancellation happens only between pages; interruption recovery discards only the page that was running.
- A preserved page retains its source and stable error code and never fabricates regions.
- Unknown JSON fields are rejected so schema drift is explicit.
- Model identity, runtime revision, preprocessing, and thresholds participate in cache identity.

## Next slices

OCR and semantic classification can consume protected detection regions without changing this contract. Translation, artwork cleanup, typesetting, quality scoring, and flattened image export remain independent, reportable stages so each can be retried without mutating source pages or repeating valid earlier work.

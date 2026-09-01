# Masumi Foundation Design

Date: 2026-07-13

Status: Approved foundation slice derived from the accepted production architecture

## Purpose

The first implementation slice establishes the non-destructive project substrate that every later detection, OCR, translation, cleanup, typesetting, quality, and export stage will use. It produces an installable Android application, but it intentionally performs no model inference and no network requests.

The slice is successful when a user can choose a chapter folder, Masumi can copy the supported pages into an immutable private workspace, and the application can persist a deterministic project manifest plus machine- and human-readable import reports.

## Approaches considered

### 1. Android application plus a pure Kotlin core module — selected

The Android module owns Storage Access Framework integration and a minimal screen. A pure Kotlin/JVM module owns ordering, opaque ID allocation, immutable import, manifest records, atomic persistence, and report generation.

This creates an installable product immediately while keeping the correctness-critical logic fast to test on the host. Later Android services and model runners can depend on the same core without coupling it to an Activity or `ContentResolver`.

### 2. Android-only single module

This has the smallest initial Gradle structure, but it would mix Android URI access with project invariants and make failure injection and deterministic unit testing harder. It would also encourage model and UI concerns to accumulate in the same module.

### 3. Desktop or command-line prototype first

This would make local iteration easy, but it would postpone the real Android storage and lifecycle constraints. The product target is Android, so a desktop-first shell would create an avoidable second integration step.

## Module boundaries

### `pipeline-core`

`pipeline-core` is a Kotlin/JVM library with no Android imports. It owns:

- supported page filtering and deterministic natural ordering;
- single-pass source copying with exact byte-length accounting;
- immutable, ID-addressed source storage;
- manifest and report domain records;
- JSON encoding and decoding;
- atomic file replacement;
- import success and failure semantics.

It accepts source streams through a narrow `SourceCandidate` interface. The core never knows whether a stream came from an Android document URI, a test fixture, or another future source.

### `app`

`app` is an Android application. It owns:

- launching `ACTION_OPEN_DOCUMENT_TREE`;
- retaining read permission for the selected tree;
- enumerating direct child documents;
- adapting document URIs to `SourceCandidate`;
- selecting an application-private project root;
- running import work off the main thread;
- presenting import progress and the final report location.

The initial UI is deliberately small: a title, an import button, and a status area. Translation controls are added only after the underlying stages exist.

## Public identifiers and records

### Project identity

Each import creates a random UUID `projectId`. A future resume entry point opens an existing project by this stored ID instead of generating a new one.

### Page identity

`pageId` is an opaque safe ID allocated by `IdSource` before a page is copied. Filenames and file contents do not define identity. Two ordered entries with identical bytes remain independent pages with distinct IDs and immutable source objects.

### Manifest schema

`manifest.json` uses schema version `1` and contains:

- `schemaVersion`;
- `projectId`;
- `createdAtEpochMillis`;
- an ordered `pages` array.

Each page record contains:

- zero-based `order`;
- `pageId`;
- `originalName`;
- normalized `mediaType`;
- exact `byteLength`;
- project-relative `storedPath`.

The manifest contains no provider secrets, absolute filesystem paths, device information, or local test metadata.

### Import report schema

Every import attempt has a `jobId` and a terminal status of `SUCCEEDED` or `FAILED`. The JSON report records the project and job IDs, start and finish times, discovered/imported/skipped counts, byte count, warnings, and a structured terminal error when present. A text report presents the same result concisely.

Reports contain project-relative paths only. Error messages are normalized so platform paths and URI query data are not copied into public-facing output.

## Supported input

The foundation accepts direct child files whose normalized type is JPEG, PNG, or WebP. It ignores subdirectories, hidden names, and unsupported files while recording skipped counts.

Ordering uses a deterministic natural comparator. Numeric runs compare numerically, text compares case-insensitively, and the original name is the final tie-breaker. This ensures names such as `2.jpg` precede `10.jpg` without depending on provider enumeration order.

## Immutable import algorithm

1. Enumerate and adapt source candidates without opening their streams.
2. Filter unsupported or hidden entries and natural-sort accepted candidates.
3. Create a staged project directory containing `sources/`, `reports/`, and a temporary import directory.
4. For each accepted candidate, allocate a page ID and stream the bytes once into a temporary file while counting the exact length.
5. Flush the temporary file, then atomically move it to `sources/<pageId>.<extension>`.
6. Treat duplicate byte sequences as independent ordered entries; import does not compare source contents.
7. Build the ordered page records only from completed source objects.
8. Encode `manifest.json` only after every accepted page has completed.
9. Write JSON and text reports inside the staged project.
10. Atomically move the complete staged project directory into `projects/<projectId>` as the single publication step.

The source document is never modified or deleted.

## Failure semantics

An import with no supported pages fails with a stable `NO_SUPPORTED_PAGES` error. Stream, storage, serialization, and replacement failures use explicit error codes.

If any accepted page fails:

- the current temporary file is removed;
- the staged project directory is removed;
- no project directory or manifest is published;
- a failed import report is written to the workspace-level failure-report directory when it remains writable;
- the exception returned to the Android layer contains a stable code and sanitized message.

The core receives an injectable clock, ID source, and file operations boundary. Tests can therefore prove ordering, atomicity, and failure behavior without sleeps or platform dependencies.

## Android behavior

The application targets the current Android SDK and supports API 26 or newer. The release application ID is `rs.masumi.app`; debug builds add `.dev` and use the name `Masumi Dev` so development and future release data remain isolated.

On successful folder selection, the app persists read permission when the provider allows it, enumerates only direct child documents, and imports them into internal application storage. The import button is disabled while work is running. Status updates return to the main thread.

The first screen reports only safe summaries: page count, bytes copied, terminal state, and the project-relative report name. It does not expose absolute paths.

## Testing strategy

Host unit tests use synthetic byte streams and temporary directories. They cover:

- natural ordering and deterministic tie-breaking;
- supported file filtering;
- opaque page IDs and exact byte counts;
- independent source objects for duplicate ordered entries;
- manifest JSON round trips;
- success report aggregation;
- failure report error codes;
- no manifest publication after an injected mid-copy failure;
- no absolute paths in serialized records or text reports.

Android verification covers debug APK assembly, installation, and launch on a connected arm64 device. Model, OCR, translation, image cleanup, rendering, and export quality tests belong to later slices.

## Deferred work

This slice does not implement:

- archive or PDF import;
- image decoding, EXIF normalization, or dimension extraction;
- detection, classification, OCR, or translation;
- artifact dependency keys beyond the source manifest;
- process-resumable stage scheduling;
- cleanup, rendering, or flattened export;
- CBZ or PDF packaging;
- production visual design.

Those features depend on the immutable project records established here and will be added as separate testable slices.

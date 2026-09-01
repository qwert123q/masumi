# Runtime Slimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the production visual-quality stage and redundant whole-file verification without weakening translation, OCR, cleanup, model loading preconditions, cache lineage, or external export publication safety.

**Architecture:** The pipeline becomes `TYPESETTING -> EXPORT`. Source-file preflight is a small shared module that validates safe path, existence, and recorded length without rereading complete files. Local immutable artifact stores trust atomic writes and validate metadata; the Android SAF destination writes each output once, closes it, checks its name and expected non-zero length, and validates the exact filename set before generation promotion.

**Tech Stack:** Kotlin/JVM, Android SDK, kotlinx.serialization, JUnit, Gradle.

**Spec:** `docs/product-optimization-requirements.md`

## Global Constraints

- Preserve the existing dirty worktree and unrelated user changes; do not reset, commit, or push.
- Never run connected instrumentation, uninstall or clear `rs.masumi.app.dev`, or use an install path other than `tools/install-debug-preserving-data.sh`.
- Never start or resume manga processing. `4008174` remains `PAUSED/USER_PAUSED`; `3585406` remains untouched.
- Retain translation fail-fast behavior, the single semantic item repair, OCR crop quality selection, cleanup safety rollback, model filename/length/structure/capability checks, and explicit dependency records.
- Use `apply_patch` for source edits and prove behavior changes with a red-green test cycle.

---

### Task 1: Retire the runtime quality stage

**Files:**
- Modify: `app/src/main/java/rs/masumi/app/pipeline/ProjectPipelineStateReader.kt`
- Modify: `app/src/main/java/rs/masumi/app/pipeline/PipelineSchedulePlanner.kt`
- Modify: `app/src/main/java/rs/masumi/app/pipeline/PipelineSchedulerService.kt`
- Modify: `app/src/main/java/rs/masumi/app/detection/ProjectCatalog.kt`
- Modify: `app/src/main/java/rs/masumi/app/pipeline/WorkspaceJanitor.kt`
- Modify: `app/src/main/java/rs/masumi/app/library/LibraryActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/java/rs/masumi/app/quality/*.kt`
- Delete: `pipeline-core/src/main/kotlin/rs/masumi/core/quality/*.kt`
- Delete or update their focused tests and quality-only strings.
- Test: `app/src/test/java/rs/masumi/app/pipeline/ProjectPipelineStateReaderTest.kt`

**Interfaces:**
- Consumes: a published typesetting run.
- Produces: `ProjectPipelineState.nextStage == PipelineStage.EXPORT` when no matching successful export exists.

- [x] Change the state-reader test to expect `EXPORT` immediately after typesetting and run it to observe the old `QUALITY` result fail.
- [x] Remove `PipelineStage.QUALITY`, its scheduler lane, broadcast receiver, foreground service, manifest entry, catalog interface, completion presentation, strings, and quality-only core contracts/stores.
- [x] Remove automatic quality-directed re-typesetting and update janitor behavior so legacy quality artifacts are not treated as current pipeline dependencies.
- [x] Run the pipeline planner, state-reader, completion-presentation, and janitor tests until green.

### Task 2: Stop cross-stage source rereads

**Files:**
- Create: `app/src/main/java/rs/masumi/app/pipeline/SourceFilePreflight.kt`
- Create: `app/src/test/java/rs/masumi/app/pipeline/SourceFilePreflightTest.kt`
- Modify: `app/src/main/java/rs/masumi/app/detection/DetectionRunner.kt`
- Modify: `app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt`
- Modify: `app/src/main/java/rs/masumi/app/cleanup/CleanupRunner.kt`
- Modify: `app/src/main/java/rs/masumi/app/typesetting/TypesettingRunner.kt`

**Interfaces:**
- Produces: `SourceFilePreflight.resolve(projectDirectory: Path, page: PageRecord): Path`, validating containment, regular-file existence, and `byteLength` only.
- Removes: every stage-local full-content source verification pass and its mismatch branch.

- [x] Add a test that replaces a source with different bytes of the same length and assert preflight still succeeds; run it and observe the missing module fail.
- [x] Implement the preflight module and use it in detection, OCR, and cleanup.
- [x] Remove typesetting's unused original-source preflight entirely; it consumes the published cleanup image.
- [x] Run the focused source/preflight and runner tests until green.

### Task 3: Collapse local artifact and SAF export verification

**Files:**
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/cleanup/CleanupArtifactStore.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/typesetting/TypesettingArtifactStore.kt`
- Modify: `app/src/main/java/rs/masumi/app/typesetting/TypesettingRunner.kt`
- Modify: `app/src/main/java/rs/masumi/app/exporting/ExportRunner.kt`
- Modify: `app/src/main/java/rs/masumi/app/exporting/FolderExportDestination.kt`
- Modify: corresponding pipeline-core and Android export tests.

**Interfaces:**
- Local artifact reads validate schema, dependency lineage, safe paths, file presence, and recorded lengths without rereading complete image bytes.
- `FolderExportDestination.publish` writes a new final file once, closes it, verifies its final name and expected non-zero length, and validates the exact output-name set at generation commit.

- [x] Add or update file-system and export-contract tests for ordinary artifact reads, filename-set generation commits, duplicate-name rejection, and rename fallback cleanup; observe the old behavior fail.
- [x] Remove all in-memory and disk content-derived values from cleanup/typesetting commit, publish validation, catalog reads, and page reads.
- [x] Remove the extra full-file read in typesetting reuse/export page resolution; retain metadata lineage and decodability checks.
- [x] Remove post-write and promotion-time SAF content readbacks while retaining close success, final name/length checks, and the exact filename-set check.
- [x] Run cleanup, typesetting, export, and artifact-store tests until green.

### Task 4: Documentation, integration review, and device-safe delivery

**Files:**
- Modify: `docs/architecture/foundation.md`
- Modify: `docs/architecture/multi-project-scheduling.md`
- Modify: affected historical design documents only where they incorrectly describe current runtime behavior.

**Interfaces:**
- The documentation describes the current production path, opaque IDs, explicit dependencies, model structure/capability checks, and removed repeated content scans.

- [x] Remove current-runtime claims that export requires a quality report or that every stage rereads immutable sources.
- [x] Run `rg` to confirm no runtime `PipelineStage.QUALITY`, quality foreground service, repeated source-content helper, or duplicate export promotion content pass remains.
- [x] Run `git diff --check`, all JVM tests, Android test compilation, Lint, and Debug APK assembly.
- [x] Request an independent review and fix every Critical or Important finding.
- [x] If the phone is connected, install only through `tools/install-debug-preserving-data.sh`, then verify the queue still contains no ACTIVE entry and its sole paused reason remains `USER_PAUSED`.

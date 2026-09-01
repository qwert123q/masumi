# Hash-Free Pipeline Implementation Plan

> **For agentic workers:** Preserve the user-approved behavior in the linked spec. Use focused red-green tests before implementation and never operate on device data outside the preserving installer.

**Goal:** Remove first-party SHA-256 computations and SHA-shaped domain contracts while preserving existing projects, models, output, recovery, and reading state.

**Architecture:** All persisted identities become opaque safe IDs. New roots use UUIDs and children use persisted structural IDs. Freshness and recovery compare explicit schemas, policies, model descriptors, ordered page lineage, and upstream IDs. File handling trusts atomic writes, byte lengths, decodability, and model signatures without content digests.

**Spec:** `docs/superpowers/specs/2026-09-01-hash-free-pipeline-design.md`

**Starting checkpoint:** `4090917` on `main`

## Global constraints

- Work directly on `main` because the user explicitly requested that all current code be committed and pushed there.
- Preserve the checkpoint and never rewrite history, amend, force-push, reset, or discard unrelated work.
- Do not introduce another content hash or checksum.
- Preserve old digest-shaped IDs and model directory names as opaque legacy names; do not rewrite large files.
- Do not expose provider credentials or stage ignored backups/private files.
- Never run connected instrumentation, uninstall, or clear `rs.masumi.app.dev`.
- Never start or resume processing; `4008174` stays `PAUSED/USER_PAUSED` and `3585406` is untouched.
- Use `tools/install-debug-preserving-data.sh` for any final device installation.

## Task 1: Project, shelf, import, and cache identities

**Files:** project manifest/JSON/importer, library metadata/archive/cache, selected import integration, and focused tests.

- Add legacy-manifest coverage proving an old unknown source-digest field is ignored and the old stored path/page ID remains usable.
- Remove source digest fields and import-time hashing; assign new page IDs from `IdSource` while streaming the copy once.
- Replace shelf source fingerprints with ordinary project/source metadata and accept older shelf JSON without rewriting archived images.
- Rework archive reuse to use stable ownership, name, and length only; ambiguous candidates receive an alternate name.
- Rework the encoded image cache to persist the original key beside a UUID-named entry and safely discard unmappable legacy cache files.
- Run importer, serialization, library, archive, snapshot, and disk-cache tests.

## Task 2: Model packages and build tools

**Files:** model descriptors/stores, Android model providers, inpainting session loader, `app/build.gradle.kts`, bundled-model tools, and focused tests.

- Add tests proving a valid same-length model is accepted without a content digest and that an existing legacy-named directory is found and reused.
- Remove digest fields, hash error codes, and full-file scans from detector, OCR, text-segmenter, and neural model packages.
- Discover existing private packages beneath their storage root; use revision-named directories for new packages.
- Keep HTTPS, exact lengths, range semantics, tensor/capability validation, and atomic publication.
- Remove build/tool digest commands and constants while retaining asset existence/length checks and model construction checks.
- Run all model-package/provider tests plus bundled model verification/build configuration checks.

## Task 3: Pipeline identities, contracts, stores, and runners

**Files:** detection, OCR, translation, cleanup, typesetting, export core/app code; catalog, freshness, status broadcasts, runners, and focused tests.

- Introduce one shared safe opaque-ID validation contract and cover UUID plus legacy 64-character IDs.
- Remove SHA-named source/output/glossary fields from current contracts; JSON decoders ignore legacy extras.
- Generate and persist run/page/region/window IDs before work. Resume existing jobs with their IDs.
- Select reusable published runs and resumable jobs by explicit dependency/configuration/page-lineage equality.
- Replace algorithm-specific catalog/store/broadcast validation with safe opaque-ID validation.
- Store explicit glossary entries and provider endpoint metadata; preserve fail-fast translation behavior.
- Remove cleanup/typesetting/export image digest generation and the SAF content readback; retain byte length, atomic publication, and exact generation-name checks.
- Run all core identity/store/reducer tests and all affected Android runner/pipeline/export/translation tests.

## Task 4: Compatibility sweep and documentation

- Search first-party production/build/tool code for `MessageDigest`, SHA-256 commands, hash helpers, and SHA-shaped validators; remove every executable occurrence.
- Search current contracts for SHA-named fields and remove them. Keep any required literal legacy key only in an explicit compatibility fixture.
- Verify no MD5, CRC, xxHash, BLAKE, or other replacement digest was introduced.
- Update current architecture, release, and product requirements to describe trusted local files and opaque IDs. Leave upstream submodules unchanged.
- Run `git diff --check` and focused compatibility tests.

## Task 5: Full verification, device-safe delivery, and GitHub publication

- Run all JVM/unit tests, Android test source compilation, Lint, and Debug APK assembly from a clean-enough Gradle state.
- Request an independent whole-change review and fix all Critical/Important findings.
- If the phone is connected, snapshot provider settings and install only with `tools/install-debug-preserving-data.sh`.
- Confirm the shelf still loads, no project is active, `4008174` remains paused for `USER_PAUSED`, and no processing service starts.
- Stage the full remaining working tree, inspect the staged diff, re-run credential scans, commit to `main`, and push `main` to `origin` without force.

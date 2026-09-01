# Automatic Visual Quality Implementation Plan

> **Superseded:** This plan records a removed runtime design. Masumi now proceeds directly from `TYPESETTING` to `EXPORT`; it does not run a production visual-quality audit or gate export on a quality report. See `docs/architecture/foundation.md` for the current architecture.

**Goal:** Add a deterministic, resumable visual quality gate between typesetting and folder export.

## Task 1: Freeze quality contracts

- [x] Define versioned policy, dependency, issue, page, run, job, report, and identity contracts.
- [x] Add strict JSON, transition, recovery, identity, and publication tests.

## Task 2: Implement the pixel audit

- [x] Compare committed cleanup and flattened pages without rerunning upstream stages.
- [x] Detect invalid dimensions, missing region pixels, out-of-bounds geometry, and unexpected outside changes.
- [x] Classify protected or intentionally preserved regions as reportable warnings.

## Task 3: Add Android execution and UI

- [x] Run quality inspection in a cancellable foreground service with page checkpoints and safe broadcasts.
- [x] Show pass, warning, blocked, page, and issue totals in the main screen.
- [x] Require a non-blocked quality run before folder export.

## Task 4: Verify

- [x] Run core, build, test-APK compilation, and lint suites.
- [x] Exercise the pixel gate and recovery through Android instrumentation on device.
- [x] Scan repository content for private environment, device, corpus, and credential data.

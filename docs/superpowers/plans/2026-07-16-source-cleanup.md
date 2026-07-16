# Source Cleanup Implementation Plan

**Goal:** Produce resumable cleaned page images by removing source glyphs only where trusted OCR has a valid translation.

## Task 1: Freeze the cleanup contract

- [x] Define versioned policy, dependency, page, run, job, region outcome, and report contracts.
- [x] Derive stable page and run identities from source, translation, and cleanup policy fields.
- [x] Add strict JSON, identity, state transition, and atomic-publication tests.

## Task 2: Implement the local cleanup engine

- [x] Build page-relative regions and adaptive glyph masks from OCR geometry.
- [x] Use flat local fill for bubble text and local boundary inpainting for translated free text.
- [x] Reject empty or oversized masks and preserve unsafe regions.
- [x] Verify source pixels outside accepted masks remain unchanged.

## Task 3: Add Android execution and UI

- [x] Consume the latest compatible translation and OCR runs without repeating either stage.
- [x] Run cleanup in a foreground service with start, cancel, resume, and structured progress.
- [x] Preview cleaned or preserved pages and show safe terminal counts/errors.

## Task 4: Verify the boundary

- [x] Run core, Android instrumentation, build, and lint suites.
- [x] Validate bubble fill, free-text inpainting, policy preservation, unsafe-mask fallback, ordered duplicate-page identity, and recovery.
- [x] Freeze cleaned page artifacts before beginning Chinese typesetting.

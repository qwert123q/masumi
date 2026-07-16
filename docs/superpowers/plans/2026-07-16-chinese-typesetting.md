# Chinese Typesetting Implementation Plan

**Goal:** Produce resumable, flattened Simplified Chinese manga pages from published translation and cleanup artifacts.

## Task 1: Freeze typesetting contracts

- [x] Define versioned policy, dependency, page, run, job, region outcome, and report contracts.
- [x] Derive stable page and run identities from cleanup inputs and all layout policy fields.
- [x] Add strict JSON, identity, transition, and atomic-publication tests.

## Task 2: Implement the Android renderer

- [x] Resolve bubble or free-text layout boxes using real page geometry.
- [x] Fit centered horizontal lines and right-to-left vertical columns by binary search.
- [x] Normalize vertical punctuation and add adaptive contrast outlines for free text.
- [x] Prove that failed layouts preserve their base pixels and successful drawing stays inside its layout box.

## Task 3: Add execution and UI

- [x] Consume the latest compatible cleanup, translation, and OCR runs without repeating them.
- [x] Run typesetting in a foreground service with start, cancel, resume, and structured progress.
- [x] Preview flattened or preserved pages and expose safe terminal counts and layout details.

## Task 4: Verify on the target device

- [x] Run core, Android instrumentation, build, and lint suites.
- [x] Use application-private API settings to produce a real translation dependency when required.
- [x] Compare representative horizontal and vertical regions against the accepted reference direction.
- [x] Freeze flattened page artifacts before final chapter export.

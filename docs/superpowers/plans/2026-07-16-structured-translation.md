# Structured Translation Implementation Plan

**Goal:** Convert trusted OCR text into resumable, ID-aligned Simplified-Chinese translation artifacts while automatically preserving sound effects and uncertain regions under the active policy.

## Task 1: Freeze the OCR-to-translation input contract

- [x] Add strict translation policy, prompt, input-item, protected-region, and model-response contracts.
- [x] Derive stable translation-region identity from OCR identity and versioned policy/prompt fields.
- [x] Build ordered translation input only from terminal OCR regions.
- [x] Keep uncertain OCR protected and omit confirmed-empty regions.
- [x] Add strict JSON, identity, ordering, and active-state rejection tests.

## Task 2: Add deterministic batching and response validation

- [x] Define chapter context windows and token-budget accounting.
- [x] Build the versioned system/user prompt from stable items and a glossary snapshot.
- [x] Join responses by ID, discard extras, and isolate duplicate, missing, invalid-role, or blank results.
- [x] Preserve sound effects according to policy and require translations for dialogue/narration.
- [x] Produce validated per-item outcomes without aborting valid siblings.

## Task 3: Implement the provider boundary

- [ ] Add an OpenAI-compatible provider interface and OkHttp implementation.
- [ ] Keep endpoint and credentials out of artifacts and logs.
- [ ] Add cancellation, bounded timeouts, transient-only retries, sanitized errors, and usage capture.
- [ ] Add deterministic fake-provider tests for success, malformed JSON, partial output, timeout, and cancellation.

## Task 4: Add resumable artifacts and orchestration

- [ ] Define page, run, job, glossary, checkpoint, and terminal-report contracts.
- [ ] Include OCR, policy, prompt, sanitized model, generation, and glossary dependencies in cache identity.
- [ ] Checkpoint successful windows and pages atomically; recover only the active uncommitted window.
- [ ] Finish with protected results rather than requiring manual approval.

## Task 5: Add Android execution and UI

- [ ] Persist provider settings without placing secrets in project files.
- [ ] Run translation in a foreground service with start, cancel, resume, and progress reporting.
- [ ] Show translated/protected counts and sanitized failures without exposing credentials or paths.
- [ ] Allow a complete OCR run to launch translation without repeating OCR.

## Task 6: Verify the module boundary

- [ ] Run core, Android instrumentation, build, and lint suites.
- [ ] Validate dialogue/narration coverage, sound-effect policy, partial-response recovery, terminology consistency, and usage reporting on representative pages.
- [ ] Freeze the translation artifact contract before beginning artwork cleanup and typesetting.

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

- [x] Add an OpenAI-compatible provider interface and OkHttp implementation.
- [x] Keep endpoint and credentials out of artifacts and logs.
- [x] Add cancellation, bounded timeouts, single-attempt fail-fast transport, sanitized errors, and usage capture. (The earlier transient-retry policy was removed by the later simplicity requirement.)
- [x] Add deterministic fake-provider tests for success, malformed JSON, partial output, timeout, and cancellation.

## Task 4: Add resumable artifacts and orchestration

- [x] Define page, run, job, glossary, checkpoint, and terminal-report contracts.
- [x] Include OCR, policy, prompt, sanitized model, generation, and glossary dependencies in cache identity.
- [x] Checkpoint successful windows and pages atomically; retain the trusted prefix and rewind the complete glossary-dependent suffix after an untrusted window.
- [x] Finish with protected results rather than requiring manual approval.

## Task 5: Add Android execution and UI

- [x] Persist provider settings without placing secrets in project files.
- [x] Run translation in a foreground service with start, cancel, resume, and progress reporting.
- [x] Show translated/protected counts and sanitized failures without exposing credentials or paths.
- [x] Allow a complete OCR run to launch translation without repeating OCR.

## Task 6: Verify the module boundary

- [x] Run core, Android instrumentation, build, and lint suites.
- [x] Validate dialogue/narration coverage, sound-effect policy, partial-response recovery, terminology consistency, and usage reporting on representative pages.
- [x] Freeze the translation artifact contract before beginning artwork cleanup and typesetting.

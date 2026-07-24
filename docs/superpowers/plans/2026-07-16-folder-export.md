# Folder Export Implementation Plan

**Goal:** Export a complete manga project as verified PNG pages directly into a user-selected Android folder.

## Task 1: Freeze export contracts

- [x] Define versioned policy, dependencies, job, page outcome, report, identity, and strict JSON contracts.
- [x] Add transition, recovery, identity, and durable report tests.

## Task 2: Implement destination publication

- [x] Resolve flattened, cleanup fallback, or source fallback bytes for every manifest page.
- [x] Publish deterministic zero-padded PNG names through the Storage Access Framework.
- [x] Verify temporary and final documents by length and SHA-256 before checkpointing.

## Task 3: Add Android execution and UI

- [x] Select and persist a writable document-tree permission.
- [x] Run export in a foreground service with cancellation, resume, safe status, and progress.
- [x] Expose export readiness and terminal fallback counts in the main screen.

## Task 4: Verify

- [x] Run core, Android instrumentation, build, and lint suites.
- [x] Exercise create, reuse, replacement, and digest verification through Android's real document-provider contract on device.
- [x] Scan repository content for private environment, device, corpus, and credential data.

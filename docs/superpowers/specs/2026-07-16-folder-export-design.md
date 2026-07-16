# Masumi Folder Export Design

Date: 2026-07-16

Status: Active

## Purpose

Export every page of the current manga project directly into a user-selected Android document-tree folder. The export contains ordinary PNG files only; it does not create ZIP, CBZ, PDF, or an extra wrapper directory.

## Input and fallback boundary

- A published typesetting run is the exact dependency of an export job.
- A committed flattened page is always preferred.
- If a complete page was preserved by typesetting, export the committed cleanup page. If that is unavailable, normalize the immutable source page to PNG.
- Region-level preservation inside an otherwise committed flattened page remains visible and is already recorded by the typesetting report.
- Export never repeats detection, OCR, translation, cleanup, or typesetting.

## Destination contract

- The selected document tree is the final destination; no subfolder is created.
- Pages use manifest order and deterministic zero-padded names: `0001.png`, `0002.png`, and so on. At least four digits are used, expanding for larger projects.
- A matching existing file is reused only after its byte length and SHA-256 match the selected page.
- A changed file is first written and verified under a job-scoped temporary name. Only then may matching final names be removed and the verified temporary document be promoted.
- Every promoted file is read back and verified. A job succeeds only when the selected folder contains one digest-verified output for every manifest page.
- After the complete expected set is verified, stale numeric PNG page names outside the current range and abandoned Masumi temporary documents are removed. Non-numeric files and directories are untouched.

## Recovery and privacy

The destination tree URI is stored only in the app-private resumable job journal. Status broadcasts and the terminal report contain a one-way destination key, counts, digests, byte lengths, and safe error codes; they contain no URI, source name, OCR text, translation text, API setting, or credential.

Cancellation resets only the active page. Already verified outputs are revalidated and reused when the task resumes. A destination write failure fails safely without mutating source or internal pipeline artifacts.

## Acceptance boundary

The slice is complete when Android can select a writable folder, export and overwrite deterministic PNG pages, cancel and resume, verify every external file, report flattened/cleanup/source counts, and complete a real device export without archive support.

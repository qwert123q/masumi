# Masumi Folder Export Design

Date: 2026-07-16

Status: Active

## Purpose

Export every page of the current manga project as one complete generation directory inside a user-selected Android document-tree folder. The export contains ordinary PNG files only; it does not create ZIP, CBZ, or PDF output.

## Input and fallback boundary

- A published typesetting run is the exact upstream dependency of an export job.
- A committed flattened page is always preferred.
- If a complete page was preserved by typesetting, export the committed cleanup page. If that is unavailable, normalize the immutable source page to PNG.
- Region-level preservation inside an otherwise committed flattened page remains visible and is already recorded by the typesetting report.
- Export never repeats detection, OCR, translation, cleanup, or typesetting.

## Destination contract

- The selected document tree is the parent destination. A job writes a private staging generation and promotes it to a uniquely named `masumi-generation-*` directory only after every page succeeds.
- Pages use manifest order and deterministic zero-padded names: `0001.png`, `0002.png`, and so on. At least four digits are used, expanding for larger projects.
- Each page is written once, the document is closed successfully, and its final name and expected non-zero byte length are queried before checkpointing.
- A job succeeds only when the staging generation contains exactly one correctly named output for every manifest page; the complete directory is then promoted atomically when the provider supports rename.
- Pruning never removes numeric images or staging directories found at the selected root unless ownership by the current job can be proven. With the current post-promotion flow, pruning is a no-op because the job owns no remaining root entries.

## Recovery and privacy

The destination tree URI is stored only in the app-private resumable job journal. Status broadcasts and the terminal report contain an opaque destination ID, counts, byte lengths, and safe error codes; they contain no URI, source name, OCR text, translation text, API setting, or credential.

Cancellation resets only the active page. Already closed outputs in the current job's staging generation are reused only when their unique name and recorded byte length match. A destination write failure fails safely without mutating source, internal pipeline artifacts, or unowned entries in the selected folder.

## Acceptance boundary

The slice is complete when Android can select a writable folder, publish deterministic PNG pages as one complete generation, cancel and resume, verify names and byte lengths, report flattened/cleanup/source counts, and complete a real device export without archive support.

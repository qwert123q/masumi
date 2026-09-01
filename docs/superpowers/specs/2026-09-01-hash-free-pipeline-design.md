# Hash-Free Pipeline Design

Date: 2026-09-01

Status: Approved for implementation

## Goal

Remove every first-party SHA-256 computation and validation from Masumi without replacing it with MD5, xxHash, CRC, BLAKE, or another content digest. The app trusts user-imported files and its private workspace. The change should simplify the code and remove avoidable full-file reads while preserving the shelf, reading progress, paused work, completed output, and installed models.

## Scope

The change covers first-party production code, tests, Gradle logic, and tools under `app`, `pipeline-core`, and `tools`.

The pinned `third_party/llama.cpp` submodule remains unchanged. It is upstream source, and Masumi disables its common downloader, tools, examples, tests, and server in the Android build. Historical design documents may continue to describe the architecture that existed when they were written; current architecture and release documents must describe the hash-free behavior.

## Identity model

- Project, page, job, run, page-artifact, region, window, destination, and export identities are ordinary opaque safe IDs.
- New independent identities come from the existing `IdSource`; production uses UUIDs.
- Child identities may be derived structurally from an already-persisted run ID plus an order or index. They must not encode file contents or use another digest.
- Existing 64-character values remain valid opaque IDs. Their shape has no special meaning and is never validated as a digest.
- Artifact stores validate path containment, ownership, schema, state, and exact upstream lineage. They validate IDs with one shared safe-ID contract, not algorithm-specific regular expressions.

## Reuse and recovery

- A published result is reusable when its stored schema, explicit configuration, model references, page lineage, and upstream artifact IDs match the requested work.
- A resumable job is selected by those same explicit dependencies. Its persisted run, page, region, and window IDs are reused unchanged.
- A new job receives a new run ID and persists all child IDs before their work begins.
- No cache or recovery decision depends on recalculating canonical content or dependency digests.
- Configuration changes that are not already represented explicitly require a schema or policy revision bump.

## Project and shelf compatibility

- `PageRecord` uses `pageId`, `storedPath`, and `byteLength`; it no longer stores a source digest.
- New imports assign a page UUID while copying once to `sources/<pageId>.<extension>`.
- JSON decoders ignore removed legacy fields. Existing manifest paths and old digest-shaped page IDs remain readable without rewriting source files.
- Shelf metadata no longer stores a source fingerprint. Existing metadata remains readable and is upgraded only when naturally rewritten.
- Existing archived source filenames are not renamed. New filenames use order, page ID, and sanitized original name.
- Archive reuse trusts a unique stable page filename plus byte length. Ambiguous names receive a new deterministic suffix instead of content comparison.
- The encoded shelf/reader image cache stores its original key in metadata and uses a random safe filename. Legacy unkeyed cache entries may be discarded and rebuilt; source/output files and reading progress may not be discarded.

## Models

- Model references keep repository/package revision, filename, byte length, runtime, license, and tensor/capability contracts; digest fields are removed.
- Existing digest-named model directories are discovered by scanning the model package root and validating their ordinary metadata, filenames, lengths, modification ordering, and tensor/capability signature. Large installed models are reused in place and are not downloaded again.
- New model directories use a human-readable package or storage revision.
- Downloads retain HTTPS, range/content-length handling, exact final length, structural model loading, capability checks, and atomic publication.
- Bundled assets retain existence and byte-length checks. ONNX sessions still validate required input/output tensor shapes after loading.
- A same-length corrupted model may now reach the model loader; loader or capability failures stop immediately. This is the accepted tradeoff for removing content digests.

## Runtime files and export

- Cleanup, typesetting, and export checkpoints store paths and byte lengths, not image digests.
- Local artifacts retain atomic writes and path/lineage validation without post-write content rereads.
- Android SAF export writes the final bytes once, closes successfully, checks the final document exists with the expected non-zero length, and validates the exact generation filename set. It does not read the complete file back.
- Images are still decoded where their pixels are required. Decode checks and model tensor checks are functional preconditions, not integrity audits, and remain.

## Translation

- Provider artifacts store the normalized endpoint and model reference directly; credentials remain excluded.
- Glossary checkpoints store the explicit input/output glossary entries and window lineage. They do not store glossary digests.
- Translation region IDs inherit the persisted OCR region lineage. Window/page/run IDs are opaque persisted identities.
- Fail-fast transport behavior and the one semantic item repair remain unchanged.

## Data and device safety

- Do not clear, uninstall, or instrument the data-bearing Android app.
- Do not start or resume manga processing. Project `4008174` remains paused with reason `USER_PAUSED`; `3585406` remains untouched.
- Install only through `tools/install-debug-preserving-data.sh` after all local verification passes.
- Existing completed shelf output and reading position must remain visible after the update.

## Acceptance criteria

- First-party runtime, build, and tool code contains no SHA-256 implementation, invocation, command, or algorithm-specific validator.
- Current domain contracts contain no SHA-named fields. Literal legacy field names are allowed only inside compatibility fixtures or migrations that do not compute or validate them.
- No other content digest is introduced as a replacement.
- Existing digest-shaped IDs and directories are accepted as ordinary safe names.
- New imports and new pipeline jobs use UUID/structural identities and survive process recovery.
- Existing installed OCR, detector, segmentation, and neural inpainting models are reused without redownload.
- Focused compatibility tests, all JVM tests, Android test compilation, Lint, Debug assembly, and `git diff --check` pass.
- A device-safe reinstall preserves provider settings, shelf data, queue state, and reading progress.

# Masumi Product Optimization Requirements

Date: 2026-08-30

Status: Implemented and verified on 2026-09-01. The user cancelled the `4008174` reprocessing run; its queue remains paused and must not resume unless the user explicitly requests it in the future.

## Product priorities

- The app has one user, so optimize the real personal workflow instead of adding broad configuration or onboarding.
- Shelf and reader usability are currently more important than management workflows.
- Translation is a core capability and must receive the highest functional-quality priority.
- An improvement must not cause a large regression in another capability. Translation, cleanup, import correctness, reader smoothness, memory use, and recovery behavior remain guardrails.

## Confirmed shelf behavior

- App launch always opens the shelf. It must not jump directly into the last reader session.
- Cold launch must show a complete, interactive first shelf viewport within 1 second on the connected target phone.
- Covers in the first viewport appear together after they are ready; individual covers must not visibly pop in one by one.
- Only the first viewport blocks initial reveal. Off-screen covers preload in the background.
- A missing, corrupt, or slow cover must fall back to a stable placeholder after the launch deadline. One cover must never block the whole shelf.
- Returning from the reader preserves the previous shelf scroll position, loaded covers, and card state without another visible reload.
- A shelf card itself is not an entry target. Reading starts only through a dedicated button with a touch target of at least 48dp.
- The button label remains the generic `阅读`; it does not expose page progress or switch to a `继续阅读` label.

## Confirmed reader behavior

- Opening a previously read manga resumes its exact persisted location: anchor page plus the offset within that page.
- Reading position survives app closure and process recreation.
- `从头阅读` remains a secondary action and never replaces the default resume behavior.
- The reader is optimized for continuous vertical scrolling. Horizontal/page-turn navigation is out of scope.
- While the reader is foregrounded, translation and cleanup work may be throttled, but not cancelled. Full background throughput resumes after leaving the reader.

## Cache policy

- The app may use up to approximately 256MB of disk cache.
- Cache eviction is automatic and recency-based.
- First-viewport cover thumbnails and recently used reader content receive priority.
- Disk caching must not imply retaining the same amount of bitmap memory.

## Explicitly out of scope

- Manga import speed is not currently a problem and must not be optimized in this work.
- A Compose rewrite, horizontal reader mode, card-wide reading action, and extra progress labels are not requested.
- Do not move the cleanup text-segmentation pass ahead of OCR or reuse it as a second detection source. Do not add a second detector/opinion pipeline in this optimization; the added architectural complexity is not justified by the current evidence.

## Translation quality investigation

- Project `3585406` is the current positive reference: its overall translation result is good.
- Project `4008174` is the current failure reference: substantial original Japanese remains visible.
- The first investigation must determine where each remaining region was lost across detection, OCR, translation eligibility/provider output, cleanup, and typesetting. Do not assume that OCR is the failing stage without artifact evidence.
- The working user hypothesis is that many regions in `4008174` failed OCR, causing downstream cleanup to preserve the source Japanese.
- The desired outcome is not indiscriminate text removal. Japanese source text should be removed and replaced when the pipeline has a trustworthy translation, while uncertain regions remain diagnosable and recoverable instead of silently disappearing from the workflow.
- Current device-artifact evidence shows that cleanup, rather than OCR or provider translation, dominates the measured difference between the two reference projects. See `docs/translation-quality-investigation-2026-08-30.md`.
- End-to-end translation completeness is the quality objective and internal evaluation metric. It must not become a user-facing completion gate or warning state when a small number of regions remain unresolved.
- For a region with a trustworthy translation, source-text removal takes priority over cosmetically perfect background reconstruction: a small local smear, imperfect screentone, or similar repair artifact is preferable to restoring the complete original Japanese text.
- Preserve the source instead only when continuing the repair could damage a face or other important visual subject. The pipeline must still continue through typesetting and export, and the manga must appear as ordinarily completed on the shelf without a warning badge, special status, or separate user-facing annotation.
- Preserve unresolved-region reasons in internal artifacts for debugging and future quality improvement only; they must not interrupt or add friction to the reading workflow.
- Do not apply the current serialized neural inpainter indiscriminately to every residual region. Keep fast deterministic cleanup for simple backgrounds, use precise local retries for residual glyphs, and reserve neural repair for genuinely complex backgrounds under measured time and memory budgets.
- The neural-repair time budget is an observed, cooperative run budget rather than a process-kill guarantee: inference checks and ONNX cancellation honor it, while asset loading or native session creation can still overrun. Report actual device time honestly; a true hard deadline would require a separately designed cleanup process and watchdog.
- When detection indicates text but OCR returns empty or low-confidence output, do not accept an early no-text decision after only two clean-empty attempts. Exhaust every configured crop, including the context crop, and then perform one targeted high-resolution local OCR retry only for unresolved regions.
- If the targeted OCR retry still fails, preserve the source internally and continue the remaining pipeline without a user-facing warning or completion-state change.
- Translation output validation must reject an otherwise non-blank result when it still contains Hiragana or Katakana, or when it is identical to the Japanese source text, except for pure numbers and symbols.
- Retry only invalid translation items once in a smaller batch while retaining their original window context and glossary. Do not retry or replace already-valid neighboring translations.
- If this targeted translation retry returns another structurally valid but still invalid translation, continue the downstream pipeline without a user-facing warning or completion-state change, while retaining the failure in internal artifacts. A network, timeout, HTTP, or malformed-response failure during that request still stops the stage immediately.
- Freeze the current translation provider model, base prompt, and translation style for this optimization. `3585406` is the positive regression reference; its already-good translation quality must not be traded away while addressing completeness failures seen in `4008174`.
- Limit translation-stage changes to output validation, targeted retry, correct request budgeting with the initial glossary included, and race-free glossary updates. Do not use the cleanup incident as justification for broad prompt or style changes.

## CPA translation provider

- The primary personal setup uses a fixed public HTTPS endpoint, `https://gpt.tomorintokyo.com/v1`, backed by CLIProxyAPI (CPA) on the user's USA VPS. The Android phone must work while the Mac is shut down and without LAN discovery, USB reverse, or a tunnel through the Mac.
- Run the native CPA Linux service under systemd and bind it only to `127.0.0.1:8317`; terminate public HTTPS in an isolated Nginx virtual host. Codex CLI does not need to run as a server. The Mac-hosted CPA remains only an optional fallback.
- Authenticate the VPS with a CPA Codex auth credential. The deployed setup reuses the user's existing Mac CPA auth file with restricted ownership and mode; a fresh device-code login remains the recovery path. CPA may refresh the stored OAuth credential automatically, while revocation or an upstream account-policy change can still require reauthentication.
- CPA keeps its existing fixed API Key. Do not generate, rotate, or otherwise randomize it; never record the actual Key in this repository.
- The translation-provider form accepts private-LAN HTTP endpoints as well as HTTPS endpoints, while public cleartext HTTP remains invalid.
- Reuse the provider model list and model selector so the user can choose among the models exposed by CPA.
- Provide a manual `测试连接与试译` action. It reads the currently entered endpoint, Key, and model without saving the form or starting/resuming the manga pipeline.
- The check makes one bounded Japanese-to-Simplified-Chinese request and displays either the returned translation or a concise safe error. It does not require an exact-match answer.
- The probe content is fixed and means `早上好，今天天气怎么样，一起去天守阁公园散步怎么样`; the Japanese input is `おはよう。今日の天気はどう？一緒に天守閣公園を散歩しない？`.

## Failure handling and configuration simplicity

- Every translation-provider call has exactly one transport attempt. Network failures, timeouts, HTTP failures, rate limits, server failures, and malformed successful responses stop immediately; there is no provider retry count, retry delay, exponential backoff, or `Retry-After` wait setting.
- Glossary discovery, the main translation request, and the one allowed item-quality repair all follow the same fail-fast transport rule. Provider failures are never swallowed to make a second, lower-quality request.
- Missing, duplicate, blank, or invalid-role response items are protocol failures and stop the current translation stage without batch bisection or isolated salvage requests.
- The only automatic translation re-request retained is one isolated quality repair for a structurally valid item that still contains Japanese kana or echoes its source text. Valid neighboring items are not resent. A second semantically invalid result remains internally preserved and does not become a user-facing warning.
- Any terminal stage failure, service-start failure, detected OCR process death, or scheduler stall pauses that project with its safe error code. The scheduler has no failure counter, retry deadline, or automatic backoff; continuing is an explicit user action.
- Process-recreation recovery of an uncommitted active page/window remains. It is checkpoint recovery, not an automatic retry of a terminal failure.
- Keep concurrency locks, OCR crop selection, cleanup safety rollback, explicit dependency records, atomic publication, byte-length checks, image decode checks, and model tensor/capability validation. Do not retain or replace content-derived identity or integrity values.

## Runtime slimming and verification policy

- Remove the automatic visual-quality stage from the runtime pipeline. Typesetting flows directly to export; no full-page cleaned-versus-typeset pixel audit, quality gate, or automatic quality-directed re-typesetting runs on the phone. Renderer correctness remains covered by focused tests instead of a production stage.
- Import copies each source once into app-private immutable storage and assigns an opaque ID. Later stages retain only useful path, existence, length, decode, and lineage checks.
- Cache reuse and recovery compare explicit schema versions, policies, model descriptors, ordered page lineage, and upstream artifact IDs. New root IDs are random opaque IDs; child IDs are derived structurally from their persisted parent.
- Model packages validate expected byte lengths, metadata, tensor signatures, and runtime capabilities. Existing legacy-named directories are discovered and reused without rereading large model files to derive a content summary.
- Cleanup and typesetting artifacts are written atomically and record paths and byte lengths. External folder export uses atomic generation publication, byte-length checks, and the exact expected filename set without a full-file read-back.

## Decision and implementation boundary

- Preserve this document as the source of truth for accepted decisions and append later confirmed requirements here.
- The user explicitly authorized implementation after the requirements discussion.
- Do not automatically reprocess the shelf or resume `4008174`. Preserve existing finished output and reading progress, keep `4008174` paused, and keep `3585406` unchanged as the positive regression reference. A later reprocessing run requires a new explicit user action.

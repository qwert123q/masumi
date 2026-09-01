# Masumi Structured Translation Design

Date: 2026-07-16

Status: Active

## Purpose

This slice turns immutable OCR artifacts into structured Japanese-to-Simplified-Chinese translation artifacts. Dialogue and narration are mandatory. Free-text sound effects are preserved by default, while uncertain OCR always keeps the original artwork and is recorded in the task report. The pipeline finishes without manual approval.

Artwork cleanup, text removal, typesetting, visual quality checks, and flattened image export remain later modules. Translation never edits source pixels or OCR artifacts.

## Frozen input boundary

Translation accepts only terminal OCR pages:

- `RECOGNIZED` regions become translation items using the selected normalized OCR attempt.
- `TEXT_IN_BUBBLE` receives the deterministic role hint `DIALOGUE`.
- recognized `TEXT_FREE` receives `CLASSIFY_FREE_TEXT`; the translation model must classify it as narration, sound effect, or other text.
- `NEEDS_FALLBACK` and `PRESERVED_SOURCE` become protected translation records and never expose uncertain text as trusted input.
- `NO_TEXT_CONFIRMED` produces no translation item.
- active OCR states are rejected instead of being read partially.

Every input item has a stable `translationRegionId` derived from the OCR page key, OCR region ID, translation policy, and prompt/schema revisions. Later provider or batching changes may invalidate translation artifacts without changing OCR identity.

## Translation policy

The initial policy is intentionally narrow:

- source language: Japanese;
- target language: Simplified Chinese;
- translate dialogue and narration;
- preserve readable sound effects by default;
- fully automatic approval;
- missing, malformed, or untrusted items preserve source artwork and appear in the report.

Sound-effect translation remains a versioned policy switch. Enabling it creates a different translation identity and cannot silently reuse earlier results.

## Structured model protocol

Requests use stable item IDs and chapter reading order. The model receives bounded chapter context, the current glossary snapshot, and items containing only ID, source text, and role hint. Responses use a strict object:

```json
{
  "items": [
    { "id": "...", "role": "DIALOGUE", "translation": "..." }
  ],
  "glossaryUpdates": {}
}
```

Results are joined by ID, never by array position or item count. Unknown IDs are discarded. Duplicate IDs, invalid roles, blank required translations, and missing requested IDs make the current window fail immediately instead of triggering salvage requests. Sound-effect items may deliberately return no translation when the active policy preserves them.

The prompt distinguishes `items` from read-only `context`: every requested item ID must appear exactly once, while context IDs must never be returned. An in-box dialogue item cannot be relabeled by the model. Free text may be classified as dialogue, narration, sound effect, or other text. Dialogue and narration are mandatory policy invariants; disabling either is rejected by the contract.

## Context, glossary, and batching

The coordinator processes pages in manifest order and builds deterministic chapter windows under a configurable token budget. A window includes limited preceding context and a glossary snapshot so names and speech style stay consistent without sending the whole chapter repeatedly.

The first batching revision uses a conservative, deterministic UTF-8 byte estimate, includes the complete system and JSON user messages, and defaults to a `6000` estimated-input-token ceiling with at most `24` preceding context items. The planner greedily fills a window in page/reading order, then trims oldest context before declaring a single item oversized. Oversized source text is isolated and marked rather than truncated or merged into another item.

Glossary updates are validated and merged serially after a successful window. A committed window records the exact normalized glossary entries it received and produced. Re-running with a changed OCR artifact, policy, prompt, model, generation settings, or glossary snapshot selects new work through those explicit dependencies.

## Provider boundary

The first network implementation targets an OpenAI-compatible chat-completions API behind a narrow provider interface. Endpoint and credentials are runtime configuration and never enter project artifacts, reports, logs, repository files, or cache identities. Artifacts may record a sanitized model identifier, protocol revision, prompt revision, generation settings, usage counters, and latency.

The client must use bounded timeouts, cancellation, exactly one transport attempt per call, redact credentials from errors, and record prompt/completion token usage when the provider returns it. Batching and cache reuse are the primary cost controls; correctness does not depend on a particular commercial provider.

The first provider implementation uses OkHttp and accepts either an API base path or a complete `/chat/completions` endpoint. It appends only `/chat/completions`; it never guesses or inserts `/v1`. Requests use bearer authentication, two chat messages, configurable JSON-object response format, bounded output tokens, and deterministic sampling settings. Runtime settings redact the endpoint, key, and model from `toString()`.

Network failures, timeouts, every non-success HTTP response, and structurally invalid successful responses fail after the first request. There is no provider retry/backoff configuration or `Retry-After` wait. Cancellation closes the active OkHttp call. Public exceptions contain only a safe code, optional HTTP status, and attempt count; response bodies, URLs, credentials, and underlying exception messages are deliberately not retained.

## Recovery and reporting

Translation checkpoints at the window and page boundaries. Ordinary process loss repeats only the active uncommitted window. A terminal provider/protocol failure pauses the project and is not retried by the scheduler. Legacy untrusted checkpoints are never published; manual continuation creates or recovers work from the last trusted boundary. A terminal report records translated, protected, usage, and duration totals without credentials.

A provider failure never triggers an automatic retry or fallback. A structurally valid item that still contains Japanese or echoes its source may receive one isolated quality repair; if that result is still semantically invalid, the source artwork is retained and the automatic pipeline may finish with a protected-result status.

Each window checkpoint atomically stores its validated item outcomes, complete normalized input and output glossary entries, safe provider metadata, token usage, attempt count, and duration before the job journal advances. The next window accepts only the previous trusted checkpoint's exact output glossary as its input. Recovery deletes stale window and affected-page checkpoints before journalling the rewound suffix, so an interrupted cleanup is safe to repeat and later windows cannot reuse results based on an obsolete glossary.

Page artifacts join terminal window outcomes back to the original page and carry OCR-protected regions separately. After every page is committed, the run publisher atomically exposes `artifact.json`, page translation JSON, the final `glossary.json`, and `report.json`. Reuse compares the OCR run/page IDs, explicit policy and prompt fields, batching limits, protocol, sanitized model, generation settings, initial glossary entries, stable item source text, and role hints; endpoint and credentials remain excluded.

## Acceptance boundary

This module is complete when it can consume a published OCR run, produce strict and resumable page/run translation artifacts, translate all trusted dialogue and narration, preserve configured sound effects and all uncertain OCR, keep chapter terminology coherent, report usage, and finish without manual review.

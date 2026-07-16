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

Results are joined by ID, never by array position or item count. Unknown IDs are discarded. Duplicate IDs, invalid roles, blank required translations, and missing requested IDs are isolated to those items; valid siblings still commit. Sound-effect items may deliberately return no translation when the active policy preserves them.

The prompt distinguishes `items` from read-only `context`: every requested item ID must appear exactly once, while context IDs must never be returned. An in-box dialogue item cannot be relabeled by the model. Free text may be classified as dialogue, narration, sound effect, or other text. Dialogue and narration are mandatory policy invariants; disabling either is rejected by the contract.

## Context, glossary, and batching

The coordinator processes pages in manifest order and builds deterministic chapter windows under a configurable token budget. A window includes limited preceding context and a glossary snapshot so names and speech style stay consistent without sending the whole chapter repeatedly.

The first batching revision uses a conservative, deterministic UTF-8 byte estimate, includes the complete system and JSON user messages, and defaults to a `6000` estimated-input-token ceiling with at most `24` preceding context items. The planner greedily fills a window in page/reading order, then trims oldest context before declaring a single item oversized. Oversized source text is isolated and marked rather than truncated or merged into another item.

Glossary updates are validated and merged serially after a successful window. A committed window records the input digest and glossary digest it used. Re-running with a changed OCR artifact, policy, prompt, model, generation settings, or glossary snapshot produces a new cache key.

## Provider boundary

The first network implementation targets an OpenAI-compatible chat-completions API behind a narrow provider interface. Endpoint and credentials are runtime configuration and never enter project artifacts, reports, logs, repository files, or cache identities. Artifacts may record a sanitized model identifier, protocol revision, prompt revision, generation settings, usage counters, and latency.

The client must use bounded timeouts, cancellation, retry only transient failures, redact credentials from errors, and record prompt/completion token usage when the provider returns it. Batching and cache reuse are the primary cost controls; correctness does not depend on a particular commercial provider.

## Recovery and reporting

Translation checkpoints at the window and page boundaries. Process loss repeats only the active uncommitted window. A terminal report records translated, sound-effect-preserved, OCR-protected, missing-response, invalid-response, and provider-failure counts plus sanitized usage and duration totals.

A provider failure may retry and switch to a configured fallback later, but it never fabricates a translation. Exhausted items retain source artwork and allow the fully automatic pipeline to finish with a protected-result status.

## Acceptance boundary

This module is complete when it can consume a published OCR run, produce strict and resumable page/run translation artifacts, translate all trusted dialogue and narration, preserve configured sound effects and all uncertain OCR, keep chapter terminology coherent, report usage, and finish without manual review.

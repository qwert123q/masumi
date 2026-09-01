# Masumi Automatic Visual Quality Design

Date: 2026-07-16

Status: Superseded

> **Superseded:** This specification records a removed runtime design. Masumi now proceeds directly from `TYPESETTING` to `EXPORT`; renderer checks remain in focused tests rather than an on-device visual-quality stage. See `docs/architecture/foundation.md` for the current architecture.

## Purpose

Automatically inspect every flattened manga page after Chinese typesetting and before folder export. The stage produces a deterministic, privacy-safe report and never asks for manual approval.

## Gate boundary

The first quality gate blocks only defects that can be proven from pipeline artifacts and pixels:

- a flattened PNG cannot be decoded or its dimensions disagree with its artifact;
- a typeset layout box escapes the visible page;
- a region is recorded as typeset but no pixel changed inside its layout box;
- pixels changed outside all declared layout boxes beyond a small anti-aliasing tolerance;
- an expected page or immutable dependency is missing or invalid.

OCR-protected text, intentionally preserved artwork, and layout fallbacks are warnings. They remain visible in the final page and are counted in the report, but they do not require a person to approve the run.

## Pixel audit

The analyzer compares the committed cleanup PNG with the committed flattened PNG. It checks page dimensions, counts actual changed pixels, verifies at least one changed pixel inside every `TYPESET` layout box, and measures changes outside the union of all declared layout boxes. It never runs OCR, translation, cleanup, or typesetting again.

## Recovery and privacy

Quality work is checkpointed per page. Cancellation or process loss repeats only the active page. Identity includes the exact typesetting run, page keys, rendered image digests, and versioned quality policy.

Page artifacts and reports contain only stable IDs, digests, geometry, issue codes, severities, and counts. They contain no OCR text, translation text, source filename, API configuration, credential, or external folder URI.

## Export boundary

A later integration step makes a passed or warning-only quality run an explicit export dependency. A blocked report must prevent exporting that exact typesetting run until the responsible upstream stage produces a new artifact.

## Acceptance boundary

The slice is complete when Android can run, cancel, resume, and publish the automatic audit for a complete typesetting run; distinguish pass, warning-only, and blocked pages; expose safe progress and totals in the UI; and prove the pixel checks on device.

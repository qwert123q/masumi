# Masumi Chinese Typesetting Design

Date: 2026-07-16

Status: Active

## Purpose

This slice turns a published cleanup run into flattened Chinese manga pages. It is deterministic, resumable, and automatically approved. It never typesets over a region whose source glyphs were not safely cleaned.

## Input boundary

Typesetting joins four immutable inputs:

- the cleaned page supplies the base pixels;
- the cleanup page proves which translated regions are safe to cover;
- the translation page supplies accepted Simplified Chinese text and semantic role;
- the OCR page supplies text geometry and an associated dialogue-bubble box when available.

The exact cleanup run and every explicit layout-policy field participate in cache identity. Translation text is never copied into reports or status broadcasts.

## Layout policy

- Dialogue uses the associated bubble box when present. Free text uses a conservative page-clipped expansion of its OCR box.
- A tall layout box uses top-aligned vertical Chinese columns ordered right to left; a wide box uses centered horizontal lines.
- Vertical punctuation is normalized to Unicode vertical presentation forms before measurement and drawing.
- Both modes binary-search for the largest font size that fits the inset layout box without truncation. The readability floor is the larger of an absolute pixel minimum and a page-relative minimum.
- Dialogue uses dark text without an outline on the cleaned bubble background. Free text chooses black or white fill from local luminance and draws a contrasting outline.
- If text cannot fit above the larger of the absolute and page-relative readability floors, the region remains untypeset and is reported.

The first renderer uses Android's regular `sans-serif` family, with generous bubble inset and a page-relative size cap calibrated against the accepted reference pages. The family name, weight, renderer revision, layout thresholds, spacing, and size bounds are explicit policy fields. A bundled font can replace it later under a new policy revision.

## Recovery and publication

Pages run in manifest order inside a foreground service. A flattened PNG and strict page JSON commit together before the journal advances. Cancellation or process loss retries only the active page; committed pages are reused. A terminal run publishes atomically with page artifacts and a sanitized report.

Success with preserved regions is valid and requires no manual review.

## Acceptance boundary

The slice is complete when Android can start, cancel, resume, preview, and report typesetting without repeating OCR, translation, or cleanup; horizontal and vertical Chinese both fit deterministically; only safely cleaned translated regions receive text; sources and cleaned pages remain immutable; and final export can consume stable flattened PNG files.

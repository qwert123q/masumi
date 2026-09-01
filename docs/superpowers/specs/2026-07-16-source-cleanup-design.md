# Masumi Source Cleanup Design

Date: 2026-07-16

Status: Active

## Purpose

This slice converts a completed translation run into cleaned page images without typesetting Chinese text. It removes source glyphs only for translation items that reached `TRANSLATED`; sound effects preserved by policy, uncertain OCR, missing translations, and provider failures keep their original pixels.

## Input boundary

Cleanup joins three immutable inputs by stable identity:

- the source page supplies pixels;
- the OCR page supplies the exact text box and bubble association;
- the translation page decides whether that OCR region may be cleaned.

An item cannot be cleaned unless all three identities agree. Cleanup reuse compares the source page ID and recorded length, translation run/page IDs, and every explicit mask and fill policy field.

## Mask and fill policy

The first engine is local and deterministic. It expands each translated OCR box by a page-relative amount, estimates the local background from the region perimeter, selects high-contrast glyph pixels, closes small gaps, and rejects masks whose coverage is empty or implausibly large.

- text associated with a dialogue bubble uses a flat local-background fill;
- translated free text uses boundary-propagated local inpainting;
- only selected mask pixels change; the rest of the box and page remain byte-for-pixel equivalent before PNG encoding;
- an unsafe mask preserves the complete region and is reported instead of erasing artwork.

The engine boundary remains replaceable by a later pinned neural inpainting model. Such a change must create a new policy revision and cache identity.

## Recovery and publication

Pages are processed in manifest order by a foreground service. A cleaned page PNG and strict page JSON are committed atomically before the job journal advances. Process loss discards only the active page; committed pages are reused. A run publishes atomically with page artifacts, cleaned PNG files, and a terminal report.

Success with preserved regions is a valid terminal result and does not require manual approval.

## Acceptance boundary

The slice is complete when Android can start, cancel, resume, preview, and report a cleanup run from a completed translation without repeating OCR or translation; only translated regions are eligible for mutation; unsafe regions remain untouched; and later typesetting can consume stable cleaned page images and per-region outcomes.

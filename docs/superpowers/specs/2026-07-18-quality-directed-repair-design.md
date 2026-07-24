# Masumi Quality-Directed Repair Design

Date: 2026-07-18

Status: Active

## Purpose

Turn a deterministic blocked quality report into one bounded repair attempt that touches only blocked typesetting pages. Valid pages are copied into the new immutable typesetting run without rerunning OCR, translation, cleanup, or their pixels.

## Repair boundary

The current quality gate can prove only typesetting-output defects, so every current blocking issue routes to `TYPESETTING`:

- rendered dimensions disagree with the page artifact;
- layout geometry escapes the visible page;
- a declared typeset region has no changed pixel;
- pixels changed outside declared layout boxes beyond tolerance.

Warnings never trigger repair. Future OCR, translation, or cleanup issue codes must add explicit routing and tests before they may schedule those stages.

## Bounded policy

One quality-directed typesetting attempt is allowed for a source typesetting lineage. The retry uses a versioned conservative policy with stronger glyph weight, more dialogue inset, less free-text expansion, and a smaller outline. Its policy revision participates in page and run identity.

If the repaired run remains blocked, the final report is published and export stays disabled. The coordinator never loops and never silently escalates to an earlier stage.

## Page reuse

Only page orders named by the repair plan are rendered again. A committed unaffected page is digest-verified, then its PNG bytes are copied into the new run with new dependency and page identities. A whole-page preserved outcome is carried forward without decoding or rendering it.

## Execution

The quality foreground task runs the first audit, suppresses its intermediate blocked terminal event, executes the bounded typesetting repair, and audits the repaired run. Cancellation remains cooperative. If repair cannot start or finish, the original blocked quality result is surfaced unchanged.

## Acceptance boundary

The slice is complete when the planner routes only blocking issues, enforces one attempt, typesetting reuses unaffected pages, the quality task automatically repairs and rechecks once, export follows the repaired run only after it passes, and core plus Android tests prove the behavior.

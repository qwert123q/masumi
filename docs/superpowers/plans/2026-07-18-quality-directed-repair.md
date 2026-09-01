# Quality-Directed Repair Implementation Plan

> **Superseded:** This plan records a removed runtime design. Masumi no longer runs a production quality audit or schedules quality-directed typesetting repair; `TYPESETTING` proceeds directly to `EXPORT`. See `docs/architecture/foundation.md` for the current architecture.

**Goal:** Automatically repair only quality-blocked typesetting pages once, then rerun the deterministic quality gate.

## Task 1: Freeze repair contracts

- [x] Define explicit stage routing, bounded policy, repair plan, and conservative retry policy identity.
- [x] Test warnings, repairable blockers, mixed pages, and exhausted lineages.

## Task 2: Reuse unaffected pages

- [x] Add exact published-typesetting lookup.
- [x] Copy verified committed pages into a new run under new identities.
- [x] Carry forward whole-page preservation without decoding or rendering it.

## Task 3: Automate repair and recheck

- [x] Suppress the intermediate blocked terminal event while repair is active.
- [x] Render only blocked pages and rerun quality against the exact repaired run.
- [x] Stop after one attempt and keep export disabled if blockers remain.

## Task 4: Verify

- [x] Run core, Android build, lint, and instrumentation suites.
- [x] Verify no private corpus, device, environment, or credential data is added.

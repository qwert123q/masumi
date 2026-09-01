# Product optimization resume checkpoint

Date: 2026-08-30

Status: Resolved on 2026-09-01 after the phone was reconnected. This is now a
historical handoff record; `docs/product-optimization-requirements.md` is the
current source of truth. The user later cancelled the `4008174` reprocessing
run, so that project must remain paused unless the user explicitly resumes it.

## State recorded at the pause

- Accepted requirements are preserved in `docs/product-optimization-requirements.md`.
- Shelf, continuous reader, OCR retry, translation validation/retry, cleanup,
  neural fallback budgeting, atomic export, artifact freshness, model pinning,
  and data-preserving installer changes are implemented in the working tree.
- The final focused host tests passed, including OCR selection invariants,
  pipeline QUALITY reachability, and reader decode-failure retry.
- The full host verification passed after the last fixes: unit tests, lint,
  debug APK, instrumentation-test compilation, and exact bundled-model filename/length and tensor-shape checks.
- Before installation, the app was force-stopped and the automatic pipeline
  queue was confirmed empty.
- Private provider, queue, library, and reading settings plus current lineage
  and public-output byte-length baselines for `3585406` and `4008174` were recorded in
  the repository's ignored private backup area. The active baseline is located
  through the ignored `.device-backups/.active-optimization-baseline` pointer.
- The optimized debug APK had been installed successfully using only
  `tools/install-debug-preserving-data.sh`; Android reported a successful
  in-place update, and the installer verified/restored provider settings.
- At that moment, the app had been force-stopped again after installation. No
  project was enqueued and the optimized UI had not yet been launched.

## Resolution after reconnecting

- The same device and data-bearing package were reverified. Installation and
  subsequent updates used only `tools/install-debug-preserving-data.sh`.
- The current working tree passed `tools/verify-masumi.sh compile`, including
  unit tests, lint, debug APK creation, instrumentation-test compilation, and
  bundled-model filename/length and tensor-shape checks.
- The optimized app was launched and returned to the shelf. Shelf/reader
  behavior is covered by the focused host and layout tests; a live reader
  interaction against `3585406` was deliberately not repeated because it
  could change the user's saved reading position.
- The user cancelled the planned `4008174` reprocessing. Its sole queue entry
  is `PAUSED` with `USER_PAUSED`; saving and testing the new translation
  provider did not change the queue bytes or start a pipeline service.
- `CPA GPT` is configured as a separate selectable provider at the public HTTPS
  CPA endpoint. Its manual fixed-sentence connectivity test succeeded without
  saving the form or resuming manga work.
- The former resume steps that would enqueue and reprocess `4008174` are
  intentionally cancelled, not pending.

## Continuing safety invariants

- Never run `connectedDebugAndroidTest`, uninstall or clear the data-bearing
  package, or use an alternate install path.
- Do not expose provider credentials or files from the ignored private backup
  area.
- Do not automatically activate or rewrite the paused `4008174` queue entry.
- If the queue contains any unexpected project in a future session, stop and
  ask the user instead of editing its XML directly.

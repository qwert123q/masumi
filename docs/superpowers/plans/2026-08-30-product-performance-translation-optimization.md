# Masumi Product Performance and Translation Optimization Plan

> Execution is already authorized. This plan is implemented in the existing
> dirty `main` checkout because the user's import and cleanup changes overlap
> the requested work. Preserve those changes, do not stash/reset them, and do
> not commit on the user's behalf.
>
> **Completion override (2026-09-01):** The user cancelled the live
> `4008174` reprocessing run. Keep its queue entry `PAUSED/USER_PAUSED`; do not
> execute the rerun, report comparison, or visual sampling steps below unless
> the user gives a new explicit instruction to resume it.

**Goal:** Make the personal reading flow feel immediate and predictable, keep
vertical reading position across restarts, prevent reading from competing with
the processing pipeline, and substantially reduce untranslated Japanese in
`4008174` without regressing the already-good `3585406` behavior or turning
neural repair into the default path.

**Source of truth:**

- `docs/product-optimization-requirements.md`
- `docs/translation-quality-investigation-2026-08-30.md`

**Safety boundary:** Never clear/uninstall the data-bearing app, never run
`connectedDebugAndroidTest` on the physical phone, never expose provider
settings, and install only through `tools/install-debug-preserving-data.sh`.
Neither project is automatically reprocessed after implementation. `4008174`
stays paused and `3585406` stays untouched.

---

## Task 1: Preserve the checkout and establish verifiable seams

**Files:**

- Create: `.superpowers/subagent-driven-development/ledger.md`
- Verify only: existing app and pipeline-core tests

1. Record the current branch, dirty paths, baseline commit, device serial and
   project ids in the local execution ledger without copying secrets.
2. Record the ruling that isolated worktrees and per-task commits are unsafe
   here because uncommitted user work overlaps `LibraryActivity` and cleanup.
3. Keep each implementation slice in non-overlapping files where possible;
   review the combined diff before integration.

## Task 2: Make shelf entry atomic, cached, and button-only

**Files:**

- Modify: `app/src/main/java/rs/masumi/app/library/LibraryActivity.kt`
- Modify: `app/src/main/res/layout/item_library_project.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/rs/masumi/app/library/LibraryImageDiskCache.kt`
- Create: `app/src/main/java/rs/masumi/app/library/ShelfCoverLoadPolicy.kt`
- Create: `app/src/test/java/rs/masumi/app/library/LibraryImageDiskCacheTest.kt`
- Create: `app/src/test/java/rs/masumi/app/library/ShelfCoverLoadPolicyTest.kt`
- Update: `app/src/androidTest/java/rs/masumi/app/library/LibraryActivityLayoutTest.kt`

1. Write JVM tests that fail when the cache exceeds 256 MiB, evicts a recently
   touched reader asset before an older ordinary asset, or orders an off-screen
   cover before the initial viewport cohort.
2. Write/update layout assertions for the exact `阅读` label and a 48dp minimum
   touch target; compile the Android tests but never run them on the phone.
3. Implement a file-backed, atomic-write, size-bounded LRU cache that stores
   encoded assets only and never retains decoded cache-sized bitmaps.
4. Load the first shelf viewport first, resolve every item to a cached cover,
   decoded cover, or stable placeholder by a short deadline, then reveal the
   whole cohort at once. Continue off-screen loading afterward.
5. Avoid the unconditional cover re-decode/rebind on return from the reader
   when the project signature is unchanged, preserving scroll/card state.
6. Remove card-root click/focus navigation. Keep only the dedicated `阅读`
   button as the reader entry point, while preserving the existing import and
   management flows.

## Task 3: Make the reader vertical-only, resumable, and foreground-priority

**Files:**

- Create: `app/src/main/java/rs/masumi/app/library/ReadingLocation.kt`
- Create: `app/src/test/java/rs/masumi/app/library/ReadingLocationTest.kt`
- Modify: `app/src/main/java/rs/masumi/app/library/MangaReaderPreferences.kt`
- Modify: `app/src/main/java/rs/masumi/app/library/ContinuousReaderView.kt`
- Modify: `app/src/main/java/rs/masumi/app/library/MangaReaderActivity.kt`
- Modify: `app/src/main/res/layout/activity_manga_reader.xml`
- Create: `app/src/main/java/rs/masumi/app/library/ReaderThreading.kt`
- Create: `app/src/main/java/rs/masumi/app/pipeline/ReaderForegroundState.kt`
- Modify: `app/src/main/java/rs/masumi/app/pipeline/PipelineSchedulePlanner.kt`
- Modify: `app/src/main/java/rs/masumi/app/pipeline/PipelineSchedulerService.kt`
- Update: `app/src/test/java/rs/masumi/app/pipeline/PipelineSchedulePlannerTest.kt`
- Update: reader Android tests for continuous-only behavior

1. Add failing pure tests for clamped `(pageIndex, intraPageFraction)` anchors,
   persistent round-tripping, and reset-to-start behavior.
2. Add failing scheduler tests proving that foreground reading admits no new
   translation/cleanup stage, retains already-running work, and resumes normal
   admission after the reader exits.
3. Expose capture/restore of a normalized continuous-scroll anchor from
   `ContinuousReaderView`, persist it synchronously on lifecycle exit, and
   restore it after page geometry becomes available.
4. Force continuous vertical mode and remove paged/direction controls from the
   user flow. Add a secondary `从头阅读` control that clears the stored anchor.
5. Decode reader media on normal-priority reader threads. Keep pipeline worker
   threads background-priority; throttle only admission of new heavy stages
   while the reader is foregrounded, never cancel current work.
6. Use the shared disk cache for hot reader assets while keeping a small,
   independently bounded decoded-bitmap window.

## Task 4: Exhaust OCR evidence before protecting a detector-positive region

**Files:**

- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrContracts.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrCropPolicy.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrQualityEvaluator.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/ocr/OcrIdentity.kt`
- Modify: corresponding pipeline-core OCR tests
- Modify: `app/src/main/java/rs/masumi/app/ocr/OcrRunner.kt`
- Modify: OCR engine/JNI request boundary only as required for the targeted
  high-detail profile

1. Write failing tests showing that two clean-empty crops cannot end a
   detector-positive region before the context crop has run.
2. Add one and only one final high-detail local retry for unresolved regions.
   It must use original crop pixels and a bounded high-detail native profile;
   ordinary attempts retain the current low-cost profile.
3. A recognized high-detail retry wins. A final unresolved result is protected
   and diagnosed internally; processing continues normally.
4. Record crop, high-detail, and quality-policy inputs explicitly so stale OCR cache
   entries cannot masquerade as the optimized behavior.

## Task 5: Reject Japanese/echo translations and make retry/glossary safe

**Files:**

- Modify: translation contracts, validator, source-text helper and artifact
  identity in `pipeline-core/src/main/kotlin/rs/masumi/core/translation/`
- Modify: `TranslationBatchPlanner.kt`
- Modify: `TranslationGlossaryMemory.kt`
- Modify: corresponding pipeline-core translation tests
- Modify: `app/src/main/java/rs/masumi/app/translation/TranslationRunner.kt`

1. Add failing validator tests for Hiragana, Katakana and normalized exact
   source echoes; accept echoes that contain only numbers, whitespace,
   punctuation or symbols.
2. Add failing planner tests proving the initial glossary affects token
   estimates/window splitting, and identity tests proving validator policy
   changes invalidate cached translation artifacts.
3. Add a concurrency test with two glossary-store instances; both mappings
   must survive and the first established mapping must win.
4. Pass the initial glossary into planning. Lock the complete
   load/merge/atomic-replace glossary critical section in-process and with a
   lock file.
5. Validate normalized provider output. Retry only invalid items once in a
   smaller request retaining the original window context and glossary snapshot;
   never resend or replace valid siblings.
6. If the targeted retry is still invalid, preserve that item internally and
   continue the pipeline without a shelf badge or warning. Do not alter the
   provider model, base prompt or style.

## Task 6: Stop restoring whole Japanese regions after safe local repair

**Files:**

- Modify: `app/src/main/java/rs/masumi/app/cleanup/TextSegmentationMask.kt`
- Modify: `app/src/main/java/rs/masumi/app/cleanup/SourceCleanupEngine.kt`
- Preserve and integrate: current lazy AOT/context-mask changes
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/cleanup/CleanupContracts.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/cleanup/CleanupArtifactStore.kt`
- Modify/add: focused cleanup tests in app and pipeline-core

1. Write a failing mask test that extracts only pixels still matching source
   within the audit mask and expands that residual locally without covering
   unrelated artwork.
2. Write a failing artifact-store test that permits audited best-effort cleaned
   output only when explicitly marked as the best-effort strategy, while still
   rejecting unexplained residuals.
3. After the existing deterministic retry, run one precise residual-only local
   retry if its mask remains under the artwork-safety limit.
4. Keep AOT lazy and limited to existing large/complex eligibility. Preserve a
   copy of the best deterministic result so a failed neural attempt cannot
   revert the whole region to Japanese.
5. If residual audit still reports pixels after a safe repair, publish the best
   cleaned result with internal residual diagnostics. Restore source only when
   the retry mask is unsafe, empty, or the engine made no change.
6. Bump cleanup policy identity and attempt validation without loosening the
   artifact contract for ordinary cleaned regions.

## Task 7: Integrate, review, and verify without the physical-device test runner

1. Run focused RED/GREEN tests during each slice.
2. Run the complete clean verification set:

   ```bash
   ./gradlew --no-daemon :pipeline-core:test :app:testDebugUnitTest --rerun-tasks --console=plain
   ./gradlew --no-daemon :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
   ```

3. Review the full dirty diff for accidental import-feature regressions,
   provider prompt/model changes, secret exposure and broad cache/artifact
   validation loosening.
4. Run an independent requirements and code-quality review and fix all material
   findings before claiming completion.

## Task 8: Preserve device data, install, and close without automatic reprocessing

Execution result: the preserving-data install and shelf return completed. The
reader interaction was not repeated against `3585406` because doing so could
change its saved position; focused host/layout tests cover the reader contract.
The user explicitly cancelled steps 5-7, so they are historical test procedure,
not pending work.

1. Resolve the connected serial and package read-only. Back up only the
   `4008174` private project directory and reading-progress preferences into a
   timestamped ignored `.device-backups` directory; never read provider prefs.
2. Install with `tools/install-debug-preserving-data.sh` only.
3. On the actual phone, verify launcher-to-shelf behavior, first-viewport cohort
   reveal, button-only navigation, vertical-only scrolling, resume after force
   stop/relaunch, reset-to-start and return-to-shelf state.
4. Capture launch, shelf and reader frame/memory measurements; compare against
   the recorded baseline and reject any material cross-capability regression.
5. **Cancelled by the user.** Resolve the private project identifier at runtime, then enqueue only visible
   project `4008174`. Do not enqueue the `3585406` project.
6. **Cancelled by the user.** Wait through export, preserving the prior published output until replacement
   is complete. Capture the new OCR/translation/cleanup reports and compare the
   residual/preserved counts and stage timing with the saved baseline.
7. **Cancelled by the user.** Visually sample prior failure pages plus ordinary pages from the new export;
   verify substantially less retained Japanese and no material face/key-art
   damage before reporting the result.

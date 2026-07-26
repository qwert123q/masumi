#!/usr/bin/env bash
#
# Verification bridge for Cowork sessions.
#
# Cowork runs Claude's shell inside an isolated Linux VM, so it cannot see a
# phone plugged into this Mac and has no Android SDK. This script runs the build
# and device steps here and writes everything into tools/verify-out/ inside the
# repo, which Claude can read directly.
#
# Usage:
#   tools/verify-masumi.sh compile     # unit tests + compile only (no device)
#   tools/verify-masumi.sh device      # install + cleanup instrumentation tests
#   tools/verify-masumi.sh report      # pull cleanup/translation stats off device
#   tools/verify-masumi.sh all         # compile + device + report
#
# Nothing here fails fast: every stage records its outcome so one log carries the
# full picture.

set -u -o pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/tools/verify-out"
LOG="$OUT_DIR/verify.log"
SUMMARY="$OUT_DIR/summary.txt"
PACKAGE="rs.masumi.app.dev"
STAGE="${1:-all}"

mkdir -p "$OUT_DIR"
: >"$LOG"
: >"$SUMMARY"

note() { printf '%s\n' "$*" | tee -a "$SUMMARY" >>"$LOG"; }
banner() { printf '\n===== %s =====\n' "$*" >>"$LOG"; }

run_stage() {
  local name="$1"
  shift
  banner "$name"
  printf '$ %s\n' "$*" >>"$LOG"
  if "$@" >>"$LOG" 2>&1; then
    note "PASS  $name"
    return 0
  fi
  local code=$?
  note "FAIL  $name (exit $code)"
  return "$code"
}

note "repo:    $REPO_ROOT"
note "stage:   $STAGE"
note "date:    $(date -u '+%Y-%m-%dT%H:%M:%SZ')"

# adb is normally outside PATH on a fresh shell.
if ! command -v adb >/dev/null 2>&1; then
  for candidate in \
    "$HOME/Library/Android/sdk/platform-tools" \
    "${ANDROID_HOME:-}/platform-tools" \
    "${ANDROID_SDK_ROOT:-}/platform-tools"; do
    [ -n "$candidate" ] && [ -x "$candidate/adb" ] && PATH="$candidate:$PATH" && break
  done
fi

cd "$REPO_ROOT" || exit 1

# ---------------------------------------------------------------- compile stage
if [ "$STAGE" = compile ] || [ "$STAGE" = all ]; then
  # Pure JVM tests first: they cover the translation policy contract change and
  # every pipeline-core reducer, and they need no device.
  run_stage "pipeline-core unit tests" ./gradlew --no-daemon :pipeline-core:test
  # Compiling the app is what actually proves the Kotlin edits are sound.
  run_stage "app assembleDebug" ./gradlew --no-daemon :app:assembleDebug
fi

# ----------------------------------------------------------------- device stage
if [ "$STAGE" = device ] || [ "$STAGE" = all ]; then
  banner "adb devices"
  adb devices -l >>"$LOG" 2>&1
  device_count="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
  note "devices: $device_count"
  if [ "$device_count" = "0" ]; then
    note "SKIP  device stage (no adb device in 'device' state)"
  else
    run_stage "app installDebug" ./gradlew --no-daemon :app:installDebug
    # These six cases are the regression net for the cleanup mask thresholds.
    run_stage "SourceCleanupEngineTest" ./gradlew --no-daemon :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.cleanup.SourceCleanupEngineTest
    run_stage "CleanupRunnerTest" ./gradlew --no-daemon :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.cleanup.CleanupRunnerTest
    run_stage "MangaLibraryStoreTest" ./gradlew --no-daemon :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=rs.masumi.app.library.MangaLibraryStoreTest

    # Copy the HTML/XML test reports so failures are readable without a device.
    for report in \
      "$REPO_ROOT/app/build/reports/androidTests/connected" \
      "$REPO_ROOT/pipeline-core/build/reports/tests/test"; do
      [ -d "$report" ] && cp -R "$report" "$OUT_DIR/$(basename "$(dirname "$report")")-$(basename "$report")" 2>/dev/null
    done
    banner "test result xml"
    find "$REPO_ROOT/app/build/outputs" "$REPO_ROOT/pipeline-core/build/test-results" \
      -name '*.xml' -print 2>/dev/null >>"$LOG"
  fi
fi

# ----------------------------------------------------------------- report stage
# Reads the on-device artifacts so the cleanup outcome becomes data instead of a
# guess: which regions were cleaned, and the exact reason each one was skipped.
if [ "$STAGE" = report ] || [ "$STAGE" = all ]; then
  banner "on-device cleanup artifacts"
  if ! command -v adb >/dev/null 2>&1; then
    note "SKIP  report stage (adb not found)"
  else
    RAW="$OUT_DIR/cleanup-artifacts.txt"
    : >"$RAW"
    # run-as gives a debug build read access to its own private files.
    adb shell "run-as $PACKAGE sh -c 'find files/workspace -name cleanup.json'" \
      >"$OUT_DIR/cleanup-paths.txt" 2>>"$LOG"
    path_count="$(grep -c 'cleanup.json' "$OUT_DIR/cleanup-paths.txt" 2>/dev/null || echo 0)"
    note "cleanup.json files on device: $path_count"
    while IFS= read -r page_path; do
      [ -z "$page_path" ] && continue
      page_path="$(printf '%s' "$page_path" | tr -d '\r')"
      printf '\n--- %s\n' "$page_path" >>"$RAW"
      adb shell "run-as $PACKAGE cat '$page_path'" >>"$RAW" 2>>"$LOG"
    done <"$OUT_DIR/cleanup-paths.txt"

    if [ -s "$RAW" ]; then
      note ""
      note "region states:"
      for state in CLEANED PRESERVED_SOURCE; do
        note "  $state: $(grep -o "\"$state\"" "$RAW" | wc -l | tr -d ' ')"
      done
      note "preserve reasons:"
      for reason in MASK_EMPTY MASK_UNSAFE ENGINE_FAILED TRANSLATION_PRESERVED OCR_PROTECTED; do
        note "  $reason: $(grep -o "\"$reason\"" "$RAW" | wc -l | tr -d ' ')"
      done
      note "strategies:"
      for strategy in FLAT_LOCAL_FILL LOCAL_BOUNDARY_INPAINT; do
        note "  $strategy: $(grep -o "\"$strategy\"" "$RAW" | wc -l | tr -d ' ')"
      done
      note "policy revision in artifacts:"
      grep -o '"revision":"[^"]*"' "$RAW" | sort | uniq -c | sed 's/^/  /' | tee -a "$SUMMARY" >>"$LOG"
    fi

    # Translation roles show whether sound effects now reach cleanup at all.
    TRANSLATION="$OUT_DIR/translation-artifacts.txt"
    : >"$TRANSLATION"
    adb shell "run-as $PACKAGE sh -c 'find files/workspace -name translation.json'" \
      >"$OUT_DIR/translation-paths.txt" 2>>"$LOG"
    while IFS= read -r page_path; do
      [ -z "$page_path" ] && continue
      page_path="$(printf '%s' "$page_path" | tr -d '\r')"
      printf '\n--- %s\n' "$page_path" >>"$TRANSLATION"
      adb shell "run-as $PACKAGE cat '$page_path'" >>"$TRANSLATION" 2>>"$LOG"
    done <"$OUT_DIR/translation-paths.txt"
    if [ -s "$TRANSLATION" ]; then
      note "translation roles:"
      for role in DIALOGUE NARRATION SOUND_EFFECT OTHER_TEXT; do
        note "  $role: $(grep -o "\"$role\"" "$TRANSLATION" | wc -l | tr -d ' ')"
      done
      note "translation preserve reasons:"
      for reason in POLICY_PRESERVED MISSING_RESPONSE DUPLICATE_RESPONSE INVALID_ROLE \
        BLANK_TRANSLATION PROVIDER_FAILURE OVERSIZED_INPUT; do
        note "  $reason: $(grep -o "\"$reason\"" "$TRANSLATION" | wc -l | tr -d ' ')"
      done
    fi

    banner "recent app logcat"
    adb logcat -d -t 4000 2>/dev/null \
      | grep -Ei 'masumi|cleanup|typeset|AndroidRuntime|FATAL' \
      | tail -600 >"$OUT_DIR/logcat.txt" 2>>"$LOG"
    note "logcat lines captured: $(wc -l <"$OUT_DIR/logcat.txt" | tr -d ' ')"
  fi
fi

note ""
note "full log:  tools/verify-out/verify.log"
printf '\n----- summary -----\n'
cat "$SUMMARY"

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
#   tools/verify-masumi.sh device      # data-preserving install on a user device
#   tools/verify-masumi.sh report      # pull cleanup/translation stats off device
#   tools/verify-masumi.sh all         # compile + device + report
#
# Every stage fails fast. The report stage emits aggregate counters only; it
# never copies page artifacts, OCR/translation text, device serials, or private
# device paths into the repository.

set -euo pipefail
umask 077

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/tools/verify-out"
LOG="$OUT_DIR/verify.log"
SUMMARY="$OUT_DIR/summary.txt"
PACKAGE="rs.masumi.app.dev"
TARGET_PROJECT_LABEL="4008174"
TARGET_PROJECT_ID="${MASUMI_TARGET_PROJECT_ID:-}"
TARGET_PROJECT_ROOT=""
BASELINE_TRANSLATION_RUN_KEY="${MASUMI_BASELINE_TRANSLATION_RUN_KEY:-}"
BASELINE_CLEANUP_RUN_KEY="${MASUMI_BASELINE_CLEANUP_RUN_KEY:-}"
RUN_STARTED_EPOCH_MILLIS="${MASUMI_RUN_STARTED_EPOCH_MILLIS:-}"
STAGE="${1:-compile}"
SAFE_OPAQUE_ID_ERE='[A-Za-z0-9][A-Za-z0-9._-]{0,127}'
NEEDS_DEVICE=false

case "$STAGE" in
  compile|self-check) ;;
  device|report|all) NEEDS_DEVICE=true ;;
  *)
    echo "Usage: $0 {compile|self-check|device|report|all}" >&2
    exit 2
    ;;
esac

if [ "$NEEDS_DEVICE" = true ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "ANDROID_SERIAL is required for device, report, and all stages." >&2
  exit 2
fi
if [ "$NEEDS_DEVICE" = true ]; then
  case "$TARGET_PROJECT_ID" in
    ""|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*)
      echo "MASUMI_TARGET_PROJECT_ID must be a safe explicit target identifier." >&2
      exit 2
      ;;
  esac
  if [ "${#TARGET_PROJECT_ID}" -gt 128 ]; then
    echo "MASUMI_TARGET_PROJECT_ID is too long." >&2
    exit 2
  fi
  TARGET_PROJECT_ROOT="files/workspace/projects/$TARGET_PROJECT_ID"
  for optional_run_key in "$BASELINE_TRANSLATION_RUN_KEY" "$BASELINE_CLEANUP_RUN_KEY"; do
    if [ -n "$optional_run_key" ]; then
      case "$optional_run_key" in
        [!A-Za-z0-9]*|*[!A-Za-z0-9._-]*)
          echo "Baseline run keys must be safe opaque identifiers." >&2
          exit 2
          ;;
      esac
      if [ "${#optional_run_key}" -gt 128 ]; then
        echo "Baseline run keys are too long." >&2
        exit 2
      fi
    fi
  done
  if [ -n "$RUN_STARTED_EPOCH_MILLIS" ] && ! [[ "$RUN_STARTED_EPOCH_MILLIS" =~ ^[0-9]{1,20}$ ]]; then
    echo "MASUMI_RUN_STARTED_EPOCH_MILLIS must be an epoch-millisecond integer." >&2
    exit 2
  fi
fi

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"
find "$OUT_DIR" -type d -exec chmod 700 {} +
find "$OUT_DIR" -type f -exec chmod 600 {} +
# Older revisions copied complete private artifacts into this directory. Remove
# only those exact legacy outputs before creating the privacy-safe report.
rm -f \
  "$OUT_DIR/cleanup-artifacts.txt" \
  "$OUT_DIR/cleanup-paths.txt" \
  "$OUT_DIR/translation-artifacts.txt" \
  "$OUT_DIR/translation-paths.txt" \
  "$OUT_DIR/logcat.txt"
: >"$LOG"
: >"$SUMMARY"
chmod 600 "$LOG" "$SUMMARY"

note() { printf '%s\n' "$*" | tee -a "$SUMMARY" >>"$LOG"; }
banner() { printf '\n===== %s =====\n' "$*" >>"$LOG"; }

is_safe_opaque_id() {
  local value="$1"
  local pattern="^${SAFE_OPAQUE_ID_ERE}$"
  [[ "$value" =~ $pattern ]]
}

extract_safe_json_id() {
  local field="$1"
  local pair value
  case "$field" in
    runArtifactKey|translationRunArtifactKey) ;;
    *) return 1 ;;
  esac
  pair="$(
    grep -E -o "\"$field\"[[:space:]]*:[[:space:]]*\"$SAFE_OPAQUE_ID_ERE\"" |
      sed -n '1p'
  )" || return 1
  [ -n "$pair" ] || return 1
  value="${pair#*:}"
  value="${value#*\"}"
  value="${value%%\"*}"
  is_safe_opaque_id "$value" || return 1
  printf '%s\n' "$value"
}

validate_latest_run_selector_output() {
  local output="$1"
  local pattern="^[0-9]{20}[[:space:]]${SAFE_OPAQUE_ID_ERE}$"
  [[ "$output" =~ $pattern ]]
}

latest_published_selector_script() {
  local artifact_root="$1"
  case "$artifact_root" in
    ""|*[!A-Za-z0-9._/-]*) return 1 ;;
  esac
  printf '%s\n' "for artifact in $artifact_root/*/artifact.json; do [ -f \"\$artifact\" ] || continue; key=\"\${artifact%/artifact.json}\"; key=\"\${key##*/}\"; case \"\$key\" in \"\"|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) continue ;; esac; [ \"\${#key}\" -le 128 ] || continue; json_pair=\"\$(grep -E -o \"\\\"runArtifactKey\\\"[[:space:]]*:[[:space:]]*\\\"$SAFE_OPAQUE_ID_ERE\\\"\" \"\$artifact\" | sed -n \"1p\")\"; json_key=\"\${json_pair#*:}\"; json_key=\"\${json_key#*\\\"}\"; json_key=\"\${json_key%%\\\"*}\"; [ \"\$json_key\" = \"\$key\" ] || continue; created=\"\$(grep -E -o \"\\\"createdAtEpochMillis\\\"[[:space:]]*:[[:space:]]*[0-9]{1,20}\" \"\$artifact\" | sed -n \"1p\" | grep -E -o \"[0-9]{1,20}\$\")\"; [ -n \"\$created\" ] || continue; printf \"%020d %s\\n\" \"\$created\" \"\$key\"; done | sort | tail -n 1"
}

json_id_pair_script() {
  local field="$1"
  local remote_path="$2"
  case "$field" in
    runArtifactKey|translationRunArtifactKey) ;;
    *) return 1 ;;
  esac
  case "$remote_path" in
    ""|*[!A-Za-z0-9._/-]*) return 1 ;;
  esac
  printf '%s\n' "grep -E -o \"\\\"$field\\\"[[:space:]]*:[[:space:]]*\\\"$SAFE_OPAQUE_ID_ERE\\\"\" \"$remote_path\" | sed -n \"1p\""
}

verify_opaque_id_self_check() {
  local uuid='550e8400-e29b-41d4-a716-446655440000'
  local structured='translation.run_2-page.0001'
  local legacy fixture_root artifact_root selector_output lineage_pair
  legacy="$(printf 'a%.0s' {1..64})"

  is_safe_opaque_id "$uuid" || return 1
  is_safe_opaque_id "$structured" || return 1
  is_safe_opaque_id "$legacy" || return 1
  if is_safe_opaque_id '.leading-dot'; then return 1; fi
  if is_safe_opaque_id 'contains/slash'; then return 1; fi
  if is_safe_opaque_id "$(printf 'a%.0s' {1..129})"; then return 1; fi
  validate_latest_run_selector_output "00000000000000000042 $uuid" || return 1
  [ "$(printf '%s\n' "{\"runArtifactKey\":\"$uuid\"}" | extract_safe_json_id runArtifactKey)" = "$uuid" ] ||
    return 1
  [ "$(printf '%s\n' "{\"translationRunArtifactKey\": \"$structured\"}" |
    extract_safe_json_id translationRunArtifactKey)" = "$structured" ] || return 1
  if printf '%s\n' '{"runArtifactKey":"unsafe/value"}' |
    extract_safe_json_id runArtifactKey >/dev/null; then
    return 1
  fi

  (
    fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/masumi-opaque-self-check.XXXXXX")"
    trap 'rm -rf "$fixture_root"' EXIT
    artifact_root="$fixture_root/artifacts/translation"
    mkdir -p "$artifact_root/$uuid" "$artifact_root/$legacy" "$artifact_root/mismatched-run"
    printf '%s\n' "{\"runArtifactKey\":\"$uuid\",\"createdAtEpochMillis\":42}" \
      >"$artifact_root/$uuid/artifact.json"
    printf '%s\n' "{\"runArtifactKey\":\"$legacy\",\"createdAtEpochMillis\":41}" \
      >"$artifact_root/$legacy/artifact.json"
    printf '%s\n' '{"runArtifactKey":"different-run","createdAtEpochMillis":99}' \
      >"$artifact_root/mismatched-run/artifact.json"
    selector_output="$(sh -c "$(latest_published_selector_script "$artifact_root")")"
    [ "$selector_output" = "00000000000000000042 $uuid" ] || exit 1
    printf '%s\n' "{\"translationRunArtifactKey\":\"$structured\"}" \
      >"$fixture_root/cleanup-artifact.json"
    lineage_pair="$(
      sh -c "$(json_id_pair_script translationRunArtifactKey "$fixture_root/cleanup-artifact.json")"
    )"
    [ "$(printf '%s\n' "$lineage_pair" | extract_safe_json_id translationRunArtifactKey)" = "$structured" ] ||
      exit 1
  ) || return 1
}

redact_output() {
  local -a expressions=(-e "s|$REPO_ROOT|<repo>|g")
  if [ -n "${HOME:-}" ]; then
    expressions+=(-e "s|$HOME|<home>|g")
  fi
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    expressions+=(-e "s|$ANDROID_SERIAL|<device>|g")
  fi
  sed "${expressions[@]}" -e '/^Provider settings backup:/d'
}

run_stage() {
  local name="$1"
  shift
  banner "$name"
  printf '$ %s\n' "$*" >>"$LOG"
  if "$@" 2>&1 | redact_output >>"$LOG"; then
    note "PASS  $name"
    return 0
  else
    local code=$?
    note "FAIL  $name (exit $code)"
    return "$code"
  fi
}

verify_apk_models() {
  local apk="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  local asset expected_bytes actual_bytes
  [ -f "$apk" ] || return 1
  while read -r asset expected_bytes; do
    actual_bytes="$(unzip -p "$apk" "$asset" | wc -c | tr -d '[:space:]')"
    [ "$actual_bytes" = "$expected_bytes" ] || return 1
  done <<'EOF'
assets/models/comic-text-segmenter-512.onnx 65568382
assets/models/aot-inpainting.onnx 23009155
EOF
}

note "stage:   $STAGE"
note "date:    $(date -u '+%Y-%m-%dT%H:%M:%SZ')"

if [ "$STAGE" = self-check ]; then
  run_stage "opaque ID selector self-check" verify_opaque_id_self_check
  exit 0
fi

# adb is normally outside PATH on a fresh shell.
if ! command -v adb >/dev/null 2>&1; then
  for candidate in \
    "$HOME/Library/Android/sdk/platform-tools" \
    "${ANDROID_HOME:-}/platform-tools" \
    "${ANDROID_SDK_ROOT:-}/platform-tools"; do
    [ -n "$candidate" ] && [ -x "$candidate/adb" ] && PATH="$candidate:$PATH" && break
  done
fi

if [ "$NEEDS_DEVICE" = true ]; then
  command -v adb >/dev/null 2>&1 || {
    echo "adb was not found" >&2
    exit 1
  }
  ADB=(adb -s "$ANDROID_SERIAL")
  if ! device_state="$("${ADB[@]}" get-state 2>/dev/null)" || [ "${device_state//$'\r'/}" != device ]; then
    echo "The selected adb device is not available." >&2
    exit 1
  fi
fi

cd "$REPO_ROOT" || exit 1

# ---------------------------------------------------------------- compile stage
if [ "$STAGE" = compile ] || [ "$STAGE" = all ]; then
  run_stage "unit tests" ./gradlew --no-daemon \
    :pipeline-core:test :app:testDebugUnitTest --rerun-tasks --console=plain
  # Lint, build the app, and compile (but never execute) instrumentation tests.
  run_stage "lint and debug artifacts" ./gradlew --no-daemon \
    :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
  run_stage "pinned models in debug APK" verify_apk_models
fi

# ----------------------------------------------------------------- device stage
if [ "$STAGE" = device ] || [ "$STAGE" = all ]; then
  run_stage "data-preserving debug install" tools/install-debug-preserving-data.sh
fi

# ----------------------------------------------------------------- report stage
# Selects the latest published translation/cleanup chain and counts a fixed
# whitelist of enum tokens on-device. Only safe run metadata and aggregate
# integers cross the adb boundary; private JSON, user text, and paths do not.
latest_published_run_key() {
  local stage="$1"
  local output selector_script
  case "$stage" in
    translation|cleanup) ;;
    *) return 1 ;;
  esac
  selector_script="$(
    latest_published_selector_script "$TARGET_PROJECT_ROOT/artifacts/$stage"
  )" || return 1
  if ! output="$(
    "${ADB[@]}" shell \
      "run-as $PACKAGE sh -c '$selector_script'" \
      2>/dev/null
  )"; then
    return 1
  fi
  output="${output//$'\r'/}"
  validate_latest_run_selector_output "$output" || return 1
  printf '%s\n' "${output##* }"
}

remote_file_count() {
  local run_root="$1"
  local artifact_name="$2"
  local output
  if ! output="$(
    "${ADB[@]}" shell \
      "run-as $PACKAGE sh -c 'find $run_root -type f -name \"$artifact_name\" | wc -l'" \
      2>/dev/null
  )"; then
    return 1
  fi
  output="${output//$'\r'/}"
  output="${output//[[:space:]]/}"
  [[ "$output" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$output"
}

remote_token_count() {
  local run_root="$1"
  local artifact_name="$2"
  local token="$3"
  local output
  if ! output="$(
    "${ADB[@]}" shell \
      "run-as $PACKAGE sh -c 'find $run_root -type f -name \"$artifact_name\" -exec grep -F -o \"\\\"$token\\\"\" {} \\; | wc -l'" \
      2>/dev/null
  )"; then
    return 1
  fi
  output="${output//$'\r'/}"
  output="${output//[[:space:]]/}"
  [[ "$output" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$output"
}

remote_file_contains() {
  local remote_path="$1"
  local token="$2"
  "${ADB[@]}" shell \
    "run-as $PACKAGE grep -F -q '$token' $remote_path" \
    >/dev/null 2>&1
}

remote_created_at() {
  local remote_path="$1"
  local output
  output="$(
    "${ADB[@]}" shell \
      "run-as $PACKAGE sh -c 'grep createdAtEpochMillis $remote_path | head -n 1 | tr -cd "'"'0-9'"'"'" \
      2>/dev/null
  )"
  output="${output//$'\r'/}"
  output="${output//[[:space:]]/}"
  [[ "$output" =~ ^[0-9]{1,20}$ ]] || return 1
  printf '%s\n' "$output"
}

remote_numeric_stats() {
  local run_root="$1"
  local artifact_name="$2"
  local field="$3"
  local output
  output="$(
    "${ADB[@]}" shell \
      "run-as $PACKAGE sh -c 'find $run_root -type f -name "$artifact_name" -exec grep -E -o "\"$field\"[[:space:]]*:[[:space:]]*[0-9]+" {} \; | sed -E "s/.*:[[:space:]]*//" | awk "BEGIN { count=0; sum=0; maximum=0 } { value=\$1+0; count++; sum+=value; if (value>maximum) maximum=value } END { printf \"%d %d %d\\n\", count, sum, maximum }"'" \
      2>/dev/null
  )"
  output="${output//$'\r'/}"
  [[ "$output" =~ ^[0-9]+[[:space:]][0-9]+[[:space:]][0-9]+$ ]] || return 1
  printf '%s\n' "$output"
}

report_token_counts() {
  local title="$1"
  local run_root="$2"
  local artifact_name="$3"
  local token count
  shift 3
  note "$title:"
  for token in "$@"; do
    count="$(remote_token_count "$run_root" "$artifact_name" "$token")"
    note "  $token: $count"
  done
}

if [ "$STAGE" = report ] || [ "$STAGE" = all ]; then
  banner "privacy-safe on-device artifact counters"
  if ! "${ADB[@]}" shell "run-as $PACKAGE test -d $TARGET_PROJECT_ROOT" >/dev/null 2>&1; then
    note "FAIL  target project $TARGET_PROJECT_LABEL is unavailable"
    exit 1
  fi

  translation_run_key="$(latest_published_run_key translation)"
  cleanup_run_key="$(latest_published_run_key cleanup)"
  target_translation_run_root="$TARGET_PROJECT_ROOT/artifacts/translation/$translation_run_key"
  target_cleanup_run_root="$TARGET_PROJECT_ROOT/artifacts/cleanup/$cleanup_run_key"
  cleanup_translation_selector="$(
    json_id_pair_script translationRunArtifactKey "$target_cleanup_run_root/artifact.json"
  )" || {
    note "FAIL  cleanup translation selector is unsafe"
    exit 1
  }
  cleanup_translation_pair="$(
    "${ADB[@]}" shell \
      "run-as $PACKAGE sh -c '$cleanup_translation_selector'" \
      2>/dev/null | tr -d '\r'
  )"
  cleanup_translation_key="$(
    printf '%s\n' "$cleanup_translation_pair" | extract_safe_json_id translationRunArtifactKey
  )" || {
    note "FAIL  cleanup translation lineage is missing or unsafe"
    exit 1
  }
  if [ "$cleanup_translation_key" != "$translation_run_key" ]; then
    note "FAIL  latest cleanup does not belong to the latest translation run"
    exit 1
  fi

  if [ -n "$BASELINE_TRANSLATION_RUN_KEY" ] && [ "$translation_run_key" = "$BASELINE_TRANSLATION_RUN_KEY" ]; then
    note "FAIL  translation was not republished after the baseline snapshot"
    exit 1
  fi
  if [ -n "$BASELINE_CLEANUP_RUN_KEY" ] && [ "$cleanup_run_key" = "$BASELINE_CLEANUP_RUN_KEY" ]; then
    note "FAIL  cleanup was not republished after the baseline snapshot"
    exit 1
  fi
  if [ -n "$RUN_STARTED_EPOCH_MILLIS" ]; then
    translation_created_at="$(remote_created_at "$target_translation_run_root/artifact.json")"
    cleanup_created_at="$(remote_created_at "$target_cleanup_run_root/artifact.json")"
    if [ "$translation_created_at" -lt "$RUN_STARTED_EPOCH_MILLIS" ] ||
      [ "$cleanup_created_at" -lt "$RUN_STARTED_EPOCH_MILLIS" ]; then
      note "FAIL  latest artifacts predate the requested rerun"
      exit 1
    fi
  fi
  if ! remote_file_contains "$target_translation_run_root/artifact.json" '"schemaVersion": 2' ||
    ! remote_file_contains "$target_translation_run_root/artifact.json" '"revision": "ja-zh-hans-v5-complete-output"' ||
    ! remote_file_contains "$target_translation_run_root/artifact.json" '"revision": "target-script-source-echo-v1"'; then
    note "FAIL  latest translation does not use the current identity"
    exit 1
  fi
  if ! remote_file_contains "$target_cleanup_run_root/artifact.json" '"schemaVersion": 4' ||
    ! remote_file_contains "$target_cleanup_run_root/artifact.json" '"revision": "comic-text-segmentation-local-residual-budgeted-aot-v33"'; then
    note "FAIL  latest cleanup does not use the current policy and pinned models"
    exit 1
  fi

  cleanup_count="$(remote_file_count "$target_cleanup_run_root" cleanup.json)"
  translation_count="$(remote_file_count "$target_translation_run_root" translation.json)"
  if [ "$cleanup_count" -eq 0 ] || [ "$translation_count" -eq 0 ]; then
    note "FAIL  target project artifacts are incomplete"
    exit 1
  fi
  note "target project: $TARGET_PROJECT_LABEL"
  note "latest published translation/cleanup chain: selected"
  note "cleanup artifacts: $cleanup_count"
  note "translation artifacts: $translation_count"
  report_token_counts "cleanup region states" "$target_cleanup_run_root" cleanup.json \
    CLEANED PRESERVED_SOURCE
  report_token_counts "cleanup preserve reasons" "$target_cleanup_run_root" cleanup.json \
    MASK_EMPTY MASK_UNSAFE ENGINE_FAILED TRANSLATION_PRESERVED OCR_PROTECTED RESIDUAL_TEXT
  report_token_counts "cleanup strategies" "$target_cleanup_run_root" cleanup.json \
    FLAT_LOCAL_FILL LOCAL_BOUNDARY_INPAINT
  report_token_counts "cleanup completion modes" "$target_cleanup_run_root" cleanup.json \
    STRICT BEST_EFFORT_RESIDUAL
  report_token_counts "neural fallback outcomes" "$target_cleanup_run_root" cleanup.json \
    NOT_ATTEMPTED NOT_ELIGIBLE BUDGET_SKIPPED SUCCEEDED FAILED
  neural_succeeded="$(remote_token_count "$target_cleanup_run_root" cleanup.json SUCCEEDED)"
  neural_failed="$(remote_token_count "$target_cleanup_run_root" cleanup.json FAILED)"
  neural_attempts=$((neural_succeeded + neural_failed))
  read -r neural_elapsed_count neural_elapsed_total neural_elapsed_max <<<"$(
    remote_numeric_stats "$target_cleanup_run_root" cleanup.json neuralFallbackMillis
  )"
  note "neural fallback attempts: $neural_attempts"
  note "neural fallback elapsed total ms: $neural_elapsed_total"
  note "neural fallback longest attempt ms: $neural_elapsed_max"
  if [ "$neural_attempts" -gt 8 ]; then
    note "FAIL  neural fallback attempt budget exceeded"
    exit 1
  fi
  if [ "$neural_elapsed_total" -gt 120000 ]; then
    note "FAIL  neural fallback elapsed budget exceeded"
    exit 1
  fi
  report_token_counts "translation roles" "$target_translation_run_root" translation.json \
    DIALOGUE NARRATION SOUND_EFFECT OTHER_TEXT
  report_token_counts "translation preserve reasons" "$target_translation_run_root" translation.json \
    POLICY_PRESERVED MISSING_RESPONSE DUPLICATE_RESPONSE INVALID_ROLE BLANK_TRANSLATION \
    INVALID_TARGET_SCRIPT SOURCE_TEXT_ECHO PROVIDER_FAILURE OVERSIZED_INPUT
fi

note ""
note "full log:  tools/verify-out/verify.log"
find "$OUT_DIR" -type d -exec chmod 700 {} +
find "$OUT_DIR" -type f -exec chmod 600 {} +
printf '\n----- summary -----\n'
cat "$SUMMARY"

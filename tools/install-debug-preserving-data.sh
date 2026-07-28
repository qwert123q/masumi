#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="rs.masumi.app.dev"
BACKUP_ROOT="$REPO_ROOT/.device-backups"

if ! command -v adb >/dev/null 2>&1; then
  for candidate in \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      PATH="$(dirname "$candidate"):$PATH"
      break
    fi
  done
fi
command -v adb >/dev/null 2>&1 || {
  echo "adb was not found" >&2
  exit 1
}

if [ -n "${ANDROID_SERIAL:-}" ]; then
  ADB=(adb -s "$ANDROID_SERIAL")
else
  device_count="$(adb devices | awk 'NR>1 && $2=="device" { count += 1 } END { print count + 0 }')"
  if [ "$device_count" -ne 1 ]; then
    echo "Expected exactly one connected device; set ANDROID_SERIAL when more are attached." >&2
    exit 1
  fi
  ADB=(adb)
fi

cd "$REPO_ROOT"
./gradlew --no-daemon :app:assembleDebug
[ -f "$APK" ] || {
  echo "Debug APK was not produced: $APK" >&2
  exit 1
}

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
backup_dir="$BACKUP_ROOT/$timestamp"
mkdir -p "$backup_dir"

snapshot_private_file() {
  local relative_path="$1"
  local output_path="$2"
  if "${ADB[@]}" shell run-as "$PACKAGE" test -s "$relative_path" >/dev/null 2>&1; then
    "${ADB[@]}" exec-out run-as "$PACKAGE" cat "$relative_path" >"$output_path"
    return 0
  fi
  return 1
}

restore_private_file() {
  local input_path="$1"
  local relative_path="$2"
  local parent_path="${relative_path%/*}"
  local temporary_path="/data/local/tmp/masumi-settings-$timestamp"
  "${ADB[@]}" push "$input_path" "$temporary_path" >/dev/null
  "${ADB[@]}" shell run-as "$PACKAGE" mkdir -p "$parent_path"
  "${ADB[@]}" shell run-as "$PACKAGE" cp "$temporary_path" "$relative_path"
  "${ADB[@]}" shell rm -f "$temporary_path"
}

had_preferences=false
had_snapshot=false
if "${ADB[@]}" shell pm path "$PACKAGE" >/dev/null 2>&1; then
  if snapshot_private_file \
    "shared_prefs/translation_provider.xml" \
    "$backup_dir/translation_provider.xml"; then
    had_preferences=true
  fi
  if snapshot_private_file \
    "no_backup/translation_provider.providers.json" \
    "$backup_dir/translation_provider.providers.json"; then
    had_snapshot=true
  fi
fi

"${ADB[@]}" install -r -t "$APK"

if [ "$had_preferences" = true ] &&
  ! "${ADB[@]}" shell run-as "$PACKAGE" test -s \
    "shared_prefs/translation_provider.xml" >/dev/null 2>&1; then
  restore_private_file \
    "$backup_dir/translation_provider.xml" \
    "shared_prefs/translation_provider.xml"
fi
if [ "$had_snapshot" = true ] &&
  ! "${ADB[@]}" shell run-as "$PACKAGE" test -s \
    "no_backup/translation_provider.providers.json" >/dev/null 2>&1; then
  restore_private_file \
    "$backup_dir/translation_provider.providers.json" \
    "no_backup/translation_provider.providers.json"
fi

echo "Installed $PACKAGE without clearing app data."
if [ "$had_preferences" = true ] || [ "$had_snapshot" = true ]; then
  echo "Provider settings backup: $backup_dir"
fi

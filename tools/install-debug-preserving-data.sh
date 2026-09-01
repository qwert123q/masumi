#!/usr/bin/env bash

set -euo pipefail
umask 077

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

if [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "ANDROID_SERIAL must explicitly identify the data-bearing target device." >&2
  exit 1
fi
ADB=(adb -s "$ANDROID_SERIAL")
if [ "$("${ADB[@]}" get-state 2>/dev/null || true)" != "device" ]; then
  echo "The explicitly selected Android device is not available." >&2
  exit 1
fi

cd "$REPO_ROOT"
./gradlew --no-daemon :app:assembleDebug
[ -f "$APK" ] || {
  echo "Debug APK was not produced: $APK" >&2
  exit 1
}

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
mkdir -p "$BACKUP_ROOT"
chmod 700 "$BACKUP_ROOT"
# Repair permissions from older versions of this helper without reading or
# printing any saved provider data.
find "$BACKUP_ROOT" -type d -exec chmod 700 {} +
find "$BACKUP_ROOT" -type f -exec chmod 600 {} +
backup_dir="$(mktemp -d "$BACKUP_ROOT/${timestamp}.XXXXXX")"
chmod 700 "$backup_dir"
private_restore_temps=()

cleanup_private_restore_temps() {
  local temporary_path
  for temporary_path in "${private_restore_temps[@]:-}"; do
    [ -n "$temporary_path" ] || continue
    "${ADB[@]}" shell run-as "$PACKAGE" rm -f "$temporary_path" >/dev/null 2>&1 || true
  done
}
trap cleanup_private_restore_temps EXIT

snapshot_private_file() {
  local relative_path="$1"
  local output_path="$2"
  local partial_path="$output_path.partial"
  rm -f "$partial_path"
  if "${ADB[@]}" shell run-as "$PACKAGE" test -s "$relative_path" >/dev/null 2>&1; then
    if "${ADB[@]}" exec-out run-as "$PACKAGE" cat "$relative_path" >"$partial_path"; then
      chmod 600 "$partial_path"
      mv "$partial_path" "$output_path"
      chmod 600 "$output_path"
      return 0
    fi
    rm -f "$partial_path"
    return 1
  fi
  return 1
}

restore_private_file() {
  local input_path="$1"
  local relative_path="$2"
  local parent_path="${relative_path%/*}"
  local temporary_path="cache/masumi-settings-${timestamp}-$$-${RANDOM}.tmp"
  local restore_command
  case "$relative_path" in
    shared_prefs/translation_provider.xml|no_backup/translation_provider.providers.json) ;;
    *)
      echo "Refusing to restore an unexpected private path." >&2
      return 1
      ;;
  esac
  private_restore_temps+=("$temporary_path")
  restore_command="umask 077; mkdir -p '$parent_path' cache; cat > '$temporary_path'; chmod 600 '$temporary_path'; mv '$temporary_path' '$relative_path'; chmod 600 '$relative_path'"
  "${ADB[@]}" shell run-as "$PACKAGE" sh -c "$restore_command" <"$input_path"
}

had_preferences=false
had_snapshot=false
if "${ADB[@]}" shell pm path "$PACKAGE" >/dev/null 2>&1; then
  if ! "${ADB[@]}" shell run-as "$PACKAGE" true >/dev/null 2>&1; then
    echo "The installed app data cannot be safely inspected; installation stopped." >&2
    exit 1
  fi
  if "${ADB[@]}" shell run-as "$PACKAGE" test -s \
    "shared_prefs/translation_provider.xml" >/dev/null 2>&1; then
    snapshot_private_file \
      "shared_prefs/translation_provider.xml" \
      "$backup_dir/translation_provider.xml" || {
        echo "Could not safely snapshot existing provider preferences; installation stopped." >&2
        exit 1
      }
    had_preferences=true
  fi
  if "${ADB[@]}" shell run-as "$PACKAGE" test -s \
    "no_backup/translation_provider.providers.json" >/dev/null 2>&1; then
    snapshot_private_file \
      "no_backup/translation_provider.providers.json" \
      "$backup_dir/translation_provider.providers.json" || {
        echo "Could not safely snapshot the existing provider snapshot; installation stopped." >&2
        exit 1
      }
    had_snapshot=true
  fi
fi

"${ADB[@]}" install -r -t "$APK"

verify_or_restore_private_file() {
  local input_path="$1"
  local relative_path="$2"
  local verification_path="$backup_dir/verification-${relative_path##*/}"
  rm -f "$verification_path" "$verification_path.partial"
  if snapshot_private_file "$relative_path" "$verification_path" &&
    cmp -s "$input_path" "$verification_path"; then
    rm -f "$verification_path"
    return 0
  fi
  rm -f "$verification_path" "$verification_path.partial"
  restore_private_file "$input_path" "$relative_path"
  if ! snapshot_private_file "$relative_path" "$verification_path" ||
    ! cmp -s "$input_path" "$verification_path"; then
    rm -f "$verification_path" "$verification_path.partial"
    echo "Provider settings could not be verified after installation." >&2
    return 1
  fi
  rm -f "$verification_path"
}

if [ "$had_preferences" = true ]; then
  verify_or_restore_private_file \
    "$backup_dir/translation_provider.xml" \
    "shared_prefs/translation_provider.xml"
fi
if [ "$had_snapshot" = true ]; then
  verify_or_restore_private_file \
    "$backup_dir/translation_provider.providers.json" \
    "no_backup/translation_provider.providers.json"
fi

echo "Installed $PACKAGE without clearing app data."
if [ "$had_preferences" = true ] || [ "$had_snapshot" = true ]; then
  echo "Provider settings were backed up in the repository's ignored private backup area."
fi

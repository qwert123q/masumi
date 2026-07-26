#!/usr/bin/env bash
#
# Minimal UI navigation helper for driving the phone during verification.
#
# uiautomator reports every node's bounds, but a label can sit underneath the
# picker's fixed bottom bar, where a tap lands on the wrong control. These
# helpers scroll a label into the safe middle band before tapping it.
#
# Usage:
#   tools/uinav.sh dump                 # print labelled nodes with bounds
#   tools/uinav.sh tap "使用此文件夹"      # scroll into view if needed, then tap
#   tools/uinav.sh find "铃井"           # print center coords, or NOTFOUND

set -u -o pipefail

if ! command -v adb >/dev/null 2>&1; then
  PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
fi

TMP="${TMPDIR:-/tmp}/uinav-$$.xml"
trap 'rm -f "$TMP"' EXIT

# Anything below this is at risk of sitting under a fixed bottom bar.
SAFE_BOTTOM=2350
SAFE_TOP=400

snapshot() {
  adb shell uiautomator dump /sdcard/uinav.xml >/dev/null 2>&1
  adb shell cat /sdcard/uinav.xml >"$TMP" 2>/dev/null
}

# Prints "x y" for the center of the node whose text/content-desc matches, else NOTFOUND.
locate() {
  python3 - "$TMP" "$1" "${2:-any}" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
want = sys.argv[2]
mode = sys.argv[3]
exact, partial = [], []
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    text = re.search(r'text="([^"]*)"', node)
    desc = re.search(r'content-desc="([^"]*)"', node)
    bounds = re.search(r'bounds="([^"]*)"', node)
    label = (text.group(1) if text else '') or (desc.group(1) if desc else '')
    if not label or not bounds:
        continue
    a = [int(v) for v in re.findall(r'\d+', bounds.group(1))]
    center = ((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
    if label == want:
        exact.append(center)
    elif want in label:
        partial.append(center)
# A substring match is never a safe substitute: a long list renders lazily, so
# "Download" can be off-screen while ".xlDownload" is visible, and tapping the
# partial match silently opens the wrong directory. Callers that want a row in a
# scrolling list must ask for 'exact' and let the caller keep scrolling.
hit = exact if mode == 'exact' else (exact or partial)
print('%d %d' % hit[0] if hit else 'NOTFOUND')
PY
}

case "${1:-}" in
  dump)
    snapshot
    python3 - "$TMP" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
pkg = re.search(r'package="([^"]*)"', xml)
print('PKG:', pkg.group(1) if pkg else '?')
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    text = re.search(r'text="([^"]*)"', node)
    desc = re.search(r'content-desc="([^"]*)"', node)
    hint = re.search(r'hint="([^"]*)"', node)
    cls = re.search(r'class="([^"]*)"', node)
    bounds = re.search(r'bounds="([^"]*)"', node)
    label = ((text.group(1) if text else '').strip()
             or (desc.group(1) if desc else '').strip()
             or ('hint:' + hint.group(1) if hint and hint.group(1).strip() else ''))
    kind = cls.group(1).split('.')[-1] if cls else ''
    if label or kind == 'EditText':
        print('%-50r %-12s %s' % (label[:48], kind, bounds.group(1) if bounds else ''))
PY
    ;;
  find)
    snapshot
    locate "$2" "${3:-any}"
    ;;
  tap)
    want="$2"
    mode="${3:-exact}"
    for attempt in $(seq 1 24); do
      snapshot
      read -r x y <<<"$(locate "$want" "$mode")"
      if [ "$x" = "NOTFOUND" ]; then
        adb shell input swipe 600 2100 600 1300 250
        sleep 1
        continue
      fi
      if [ "$y" -gt "$SAFE_BOTTOM" ]; then
        # Sitting under the bottom bar: scroll it up and re-measure.
        adb shell input swipe 600 2100 600 1500 250
        sleep 1
        continue
      fi
      if [ "$y" -lt "$SAFE_TOP" ]; then
        adb shell input swipe 600 1300 600 1900 250
        sleep 1
        continue
      fi
      adb shell input tap "$x" "$y"
      echo "tapped '$want' at $x,$y"
      exit 0
    done
    echo "could not reach '$want'" >&2
    exit 1
    ;;
  *)
    echo "usage: $0 {dump|find LABEL|tap LABEL}" >&2
    exit 2
    ;;
esac

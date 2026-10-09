#!/usr/bin/env bash
set -euo pipefail
tmp=$(mktemp)
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull -q /sdcard/ui.xml "$tmp"
xy=$(python3 - "$tmp" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1]).read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1) == sys.argv[2]:
        a, b, c, d = map(int, m.groups()[1:])
        print((a + c) // 2, (b + d) // 2)
        break
PY
)
rm -f "$tmp"
[ -n "$xy" ] || { echo "not found: $1" >&2; exit 1; }
adb shell input tap $xy

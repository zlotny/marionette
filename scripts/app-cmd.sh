#!/usr/bin/env bash
set -euo pipefail
PKG=dev.marionette.debug
text="$*"
adb logcat -c
adb shell am broadcast -p $PKG -a dev.marionette.DEBUG_COMMAND --es text "$(printf %q "$text")" >/dev/null
for _ in $(seq 1 60); do
  sleep 1
  reply=$(adb logcat -d -s Marionette:I | grep DEBUG_REPLY || true)
  [ -n "$reply" ] && break
done
echo "${reply:-timeout}" | sed 's/.*DEBUG_REPLY/REPLY/'
"$(dirname "$0")/last-trace.sh" | python3 -c '
import json,sys
t=json.load(sys.stdin)
for r in t.get("rounds",[]): print("round", r.get("round"), r.get("millis"),"ms", "calls:", [c.get("name") if isinstance(c,dict) else c for c in r.get("tool_calls",[])], "tokens", r.get("input_tokens"), r.get("output_tokens"))
for x in t.get("tools",[]): print("tool", x.get("name"), x.get("millis"), "ms", json.dumps(x.get("arguments")), "->", json.dumps(x.get("result"))[:500])
print("total", t.get("total_millis"), "ms", "error:", t.get("error"))
'

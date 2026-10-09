#!/usr/bin/env bash
set -uo pipefail
out=${1:-build/s1}
mkdir -p "$out"
pin=1234
maps=com.google.android.apps.maps

unlock() {
  adb shell input keyevent KEYCODE_WAKEUP; sleep 1
  adb shell dumpsys window | grep -q "isKeyguardShowing=true" || return 0
  adb shell wm dismiss-keyguard; sleep 1
  adb shell input text $pin; adb shell input keyevent KEYCODE_ENTER; sleep 2
}

adb shell dumpsys activity services dev.marionette.spike | grep -q ListenerService || { echo "start the listener service first" >&2; exit 1; }
for v in ${VARIANTS:-1 2 3 4 5 6}; do
  unlock
  adb shell dumpsys activity services dev.marionette.spike | grep -q ListenerService || { echo "V$v: listener service not running, abort" >&2; exit 1; }
  adb shell am force-stop $maps
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell input keyevent KEYCODE_SLEEP; sleep 4
  adb logcat -c
  adb shell am broadcast -a dev.marionette.DEBUG_FIRE --ei variant "$v" >/dev/null
  sleep 10
  top=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | sed 's/^ *//')
  wake=$(adb shell dumpsys power | grep -m1 -o "mWakefulness=[A-Za-z]*")
  kg=$(adb shell dumpsys window | grep -m1 -oE "isKeyguardShowing=[a-z]+|mKeyguardShowing=[a-z]+")
  adb exec-out screencap -p > "$out/v$v.png"
  task=$(adb shell dumpsys activity activities | grep -m1 -oE "ActivityRecord\{[^}]*com.google.android.apps.maps/[^ }]*" || echo "no maps activity")
  nav=$(adb shell dumpsys activity services $maps | grep -m1 -oE "NavigationService" || echo "no maps nav service")
  focus=$(adb shell dumpsys audio | grep -A3 "Audio Focus stack" | grep -m1 -o "pack: [a-z.]*" || echo "no audio focus")
  bal=$(adb logcat -d | grep -m1 -oE "Background activity launch blocked.*|BAL_BLOCK.*|Abort background activity starts.*" | cut -c1-160)
  echo "V$v | $wake | $kg | $top"
  echo "    maps: $task | $nav | $focus"
  [ -n "$bal" ] && echo "    BAL: $bal"
  adb logcat -d -s Marionette:I | grep -E "S1|Session|trampoline" | sed 's/^.*Marionette: /    /'
  unlock; sleep 10
  after=$(adb shell dumpsys activity services $maps | grep -m1 -oE "NavigationService" || echo "no maps nav service")
  echo "    after unlock: $after"
  adb logcat -d -s Marionette:I | grep -E "USER_PRESENT|unlock fallback" | sed 's/^.*Marionette: /    /'
done

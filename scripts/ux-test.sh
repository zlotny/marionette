#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
export ANDROID_SERIAL=emulator-5554
PKG=dev.marionette.debug
WAV=build/ux/ux_speech.wav
mkdir -p build/ux
started=0
if ! adb get-state >/dev/null 2>&1; then
  scripts/emu.sh start 20
  started=1
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell input swipe 540 1800 540 600
  adb shell input text 1234
  adb shell input keyevent 66
fi
restore() {
  adb shell cmd role add-role-holder android.app.role.ASSISTANT dev.marionette 0 >/dev/null 2>&1 || true
  adb shell svc power stayon false >/dev/null 2>&1 || true
  adb shell pm clear com.google.android.deskclock >/dev/null 2>&1 || true
  [ "$started" = 1 ] && [ -z "${KEEP_EMU:-}" ] && scripts/emu.sh stop
}
trap restore EXIT
[ -n "${SKIP_INSTALL:-}" ] || ./gradlew :app:installDebug -q
if [ ! -f "$WAV" ]; then
  say -v Monica -o build/ux/speech.aiff "Pon un temporizador de cinco minutos, por favor."
  ffmpeg -loglevel error -y -i build/ux/speech.aiff -f lavfi -t 1.2 -i anullsrc=r=16000:cl=mono \
    -filter_complex "[0:a]aresample=16000,aformat=channel_layouts=mono[a];[a][1:a]concat=n=2:v=0:a=1" \
    -ar 16000 -ac 1 -c:a pcm_s16le -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$WAV"
fi
adb push -q "$WAV" "/sdcard/Android/data/$PKG/files/ux_speech.wav"
adb shell pm grant $PKG android.permission.ACCESS_LOCAL_NETWORK
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell cmd role add-role-holder android.app.role.ASSISTANT $PKG 0
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n $PKG/dev.marionette.ui.MainActivity >/dev/null
sleep 2
python3 -I tools/ux_test.py "$@"

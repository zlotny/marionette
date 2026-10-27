#!/bin/zsh
set -e
cd "$(dirname "$0")/.."
pkg=dev.marionette.debug
out=build/wakefp
mkdir -p $out/samples $out/hits
adb shell run-as $pkg cat files/wake-templates.bin > $out/wake-templates.bin
for f in $(adb shell run-as $pkg ls files/wake-samples | tr -d '\r'); do
  [[ -f $out/samples/$f ]] || adb shell run-as $pkg cat files/wake-samples/$f > $out/samples/$f
done
adb pull /sdcard/Android/data/$pkg/files/voice-test $out/ >/dev/null
adb pull /sdcard/Android/data/$pkg/files/wake-hits/. $out/hits/ >/dev/null 2>&1 || true
v=$out/voice-test
speech=($out/samples/speech-*.wav(N))
neg=($v/noise-*.wav(N))
build/venv/bin/python tools/wake_fp_eval.py --templates $out/wake-templates.bin \
  --speech $speech --pos $v/wake-*.wav --neg $neg "$@"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
export ANDROID_SERIAL=emulator-5554
PKG=dev.marionette.debug
WAV=build/badnet/q.wav
OUT=build/badnet/results.tsv
PORT=8899
mkdir -p build/badnet
declare -a PROFILES=(
  "good|"
  "3g|--up 300 --down 800 --latency 100"
  "edge|--up 60 --down 200 --latency 300"
  "gprs|--up 20 --down 50 --latency 600"
  "drops|--up 60 --down 200 --latency 300 --drop 0.5"
  "outage|--outage 40"
)
[ -n "${ONLY:-}" ] && PROFILES=($(printf '%s\n' "${PROFILES[@]}" | grep -E "^(${ONLY})\|" | tr ' ' '~'))
if [ ! -f "$WAV" ]; then
  say -o build/badnet/wake.aiff "Hey Marionette."
  say -v Monica -o build/badnet/req.aiff "Dime solo un número del uno al diez, sin nada más."
  ffmpeg -loglevel error -y -i build/badnet/wake.aiff -f lavfi -t 0.9 -i anullsrc=r=16000:cl=mono -i build/badnet/req.aiff -f lavfi -t 1.5 -i anullsrc=r=16000:cl=mono \
    -filter_complex "[0:a]aresample=16000,aformat=channel_layouts=mono[a];[2:a]aresample=16000,aformat=channel_layouts=mono[b];[a][1:a][b][3:a]concat=n=4:v=0:a=1" \
    -ar 16000 -ac 1 -c:a pcm_s16le -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$WAV"
fi
adb push -q "$WAV" "/sdcard/Android/data/$PKG/files/q.wav"
trap 'adb shell settings put global http_proxy :0 >/dev/null; kill $(jobs -p) 2>/dev/null || true' EXIT
printf "profile\tstt_bytes\tstt_ms\ttranscript_ms\tagent_retries\theard\tresult\ttotal_s\n" > "$OUT"
for entry in "${PROFILES[@]}"; do
  entry=${entry//\~/ }
  name=${entry%%|*}; opts=${entry#*|}
  python3 -I tools/badnet_proxy.py --port $PORT $opts > "build/badnet/proxy-$name.log" 2>&1 &
  proxy=$!
  sleep 0.5
  adb shell settings put global http_proxy "10.0.2.2:$PORT" >/dev/null
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell monkey -p "$PKG" 1 >/dev/null 2>&1
  timeout 30 bash -c 'until adb logcat -d -s Marionette | grep -q "Wake detector"; do sleep 0.5; done' || true
  sleep 1
  adb logcat -c
  start=$(date +%s)
  adb shell am broadcast -a dev.marionette.DEBUG_AUDIO -p "$PKG" --es path q.wav >/dev/null
  timeout ${TURN_TIMEOUT:-180} bash -c 'until adb logcat -d -s Marionette | grep -qE "Voice turn done"; do sleep 1; done' || true
  if [ "$name" = outage ]; then
    timeout 120 bash -c 'until adb logcat -d -s Marionette | grep -q "Resending"; do sleep 1; done' || true
    timeout 120 bash -c 'until [ "$(adb logcat -d -s Marionette | grep -c "Cloud speech first audio\|Speech engine")" -ge 2 ]; do sleep 1; done' || true
  fi
  log=$(adb logcat -d -s Marionette)
  printf "%s\n" "$log" > "build/badnet/app-$name.log"
  total=$(( $(date +%s) - start ))
  stt=$(grep -o "Cloud STT [0-9]* ms of audio ([0-9]* bytes) in [0-9]* ms" <<<"$log" | tail -1 || true)
  bytes=$(grep -o "([0-9]* bytes)" <<<"$stt" | grep -o "[0-9]*" || true)
  sttms=$(grep -o "in [0-9]* ms" <<<"$stt" | grep -o "[0-9]*" || true)
  tms=$(grep -o "Transcript ready [0-9]* ms" <<<"$log" | tail -1 | grep -o "[0-9]*" || true)
  heard=$(grep -o "Heard: .*" <<<"$log" | tail -1 | cut -c8-50 || true)
  retries=$(grep -c "not delivered" <<<"$log" || true)
  result=$(grep -oE "Resending|Turn failed.*|STT failed.*|STT result from [a-z]*|racing [a-z]*" <<<"$log" | tr '\n' ',' | cut -c1-80 || true)
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$name" "${bytes:-}" "${sttms:-}" "${tms:-}" "$retries" "${heard:-}" "${result:-ok}" "$total" | tee -a "$OUT"
  adb shell settings put global http_proxy :0 >/dev/null
  kill $proxy 2>/dev/null || true; wait $proxy 2>/dev/null || true
done

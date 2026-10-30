#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

LEASE=build/emu-lease
ENFORCER=build/emu-enforce.pid
SERIAL=emulator-5554
DEFAULT_MINUTES=15
MAX_MINUTES=60
GRACE_SECONDS=${EMU_GRACE_SECONDS:-300}

running() { pgrep -f "qemu-system.*marionette" >/dev/null; }

lease() {
  mkdir -p build
  local minutes=${1:-$DEFAULT_MINUTES}
  [ "$minutes" -gt "$MAX_MINUTES" ] && minutes=$MAX_MINUTES
  echo $(( $(date +%s) + minutes * 60 )) > "$LEASE"
  echo "lease until $(date -r "$(cat "$LEASE")" +%H:%M:%S), stops by itself $((GRACE_SECONDS / 60)) min later"
}

stop() {
  adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 60); do running || break; sleep 1; done
  if running; then pkill -f "qemu-system.*marionette" || true; sleep 3; fi
  rm -f "$LEASE"
  running && { echo "still running"; return 1; }
  echo stopped
}

case "${1:-}" in
  start)
    if ! running; then
      nohup emulator -avd marionette -no-boot-anim -no-window -allow-host-audio > build/emu.log 2>&1 &
      timeout 240 adb -s "$SERIAL" wait-for-device
      until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
      echo booted
    fi
    lease "${2:-}"
    if ! { [ -f "$ENFORCER" ] && kill -0 "$(cat "$ENFORCER")" 2>/dev/null; }; then
      nohup scripts/emu.sh enforce > build/emu-enforce.log 2>&1 &
      echo $! > "$ENFORCER"
    fi
    ;;
  extend)
    running || { echo "not running"; exit 1; }
    lease "${2:-}"
    ;;
  stop)
    stop
    ;;
  enforce)
    while running; do
      if [ ! -f "$LEASE" ] || [ "$(date +%s)" -ge $(( $(cat "$LEASE") + GRACE_SECONDS )) ]; then
        echo "$(date +%T) lease over, stopping"
        stop
        rm -f "$ENFORCER"
        exit 0
      fi
      sleep 20
    done
    rm -f "$ENFORCER"
    ;;
  watch)
    warned=""
    while running; do
      if [ -f "$LEASE" ] && [ "$(date +%s)" -ge "$(cat "$LEASE")" ] && [ "$warned" != "$(cat "$LEASE")" ]; then
        warned=$(cat "$LEASE")
        echo "emulator lease expired: it stops by itself in $((GRACE_SECONDS / 60)) min; run scripts/emu.sh extend [minutes] only if emulator work is still running"
      fi
      sleep 10
    done
    echo "emulator stopped"
    ;;
  status)
    if running; then
      [ -f "$LEASE" ] && echo "running, lease until $(date -r "$(cat "$LEASE")" +%H:%M:%S)" || echo "running, no lease"
    else
      echo stopped
    fi
    ;;
  *)
    echo "usage: scripts/emu.sh start|extend [minutes] | stop | watch | status" >&2
    exit 2
    ;;
esac

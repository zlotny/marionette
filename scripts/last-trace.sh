#!/usr/bin/env bash
set -euo pipefail
PKG=dev.marionette.debug
name=$(adb shell run-as $PKG ls files/traces | sort | tail -1 | tr -d '\r')
adb shell run-as $PKG cat "files/traces/$name"

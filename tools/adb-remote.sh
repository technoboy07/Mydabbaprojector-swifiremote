#!/usr/bin/env bash
# Development / diagnostic remote that runs on your PC, not on the network.
# It sends real key events with `adb shell input`, which the APK itself cannot do.
# Only a fixed allowlist of keys is accepted; nothing else is passed to the shell.
#
#   ./tools/adb-remote.sh up|down|left|right|ok|back|home|menu|volup|voldown|mute|play|next|prev
#   ./tools/adb-remote.sh text "hello"     # ASCII letters, digits and spaces only
#
# Use over USB, or `adb connect PROJECTOR_IP:5555` only on a trusted network, and turn network
# ADB off again afterwards (Settings > Developer options), because ADB has no per-app security.
set -euo pipefail
ADB="${ADB:-adb}"
key() { "$ADB" shell input keyevent "$1"; }
case "${1:-}" in
  up) key 19 ;; down) key 20 ;; left) key 21 ;; right) key 22 ;; ok) key 23 ;;
  back) key 4 ;; home) key 3 ;; menu) key 82 ;;
  volup) key 24 ;; voldown) key 25 ;; mute) key 164 ;;
  play) key 85 ;; next) key 87 ;; prev) key 88 ;;
  text)
    t="${2:-}"
    if [[ ! "$t" =~ ^[A-Za-z0-9\ ]{1,100}$ ]]; then
      echo "text: only letters, digits and spaces (max 100)" >&2; exit 2
    fi
    "$ADB" shell input text "${t// /%s}" ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac

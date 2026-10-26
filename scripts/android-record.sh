#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
capture_name="${1:-interaction}"
capture_seconds="${2:-30}"
[[ "$capture_name" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo 'Use a capture name containing letters, digits, _ or -.' >&2; exit 1; }
[[ "$capture_seconds" =~ ^[0-9]+$ ]] && (( capture_seconds >= 1 && capture_seconds <= 180 )) || { echo 'Duration must be 1–180 seconds.' >&2; exit 1; }
capture_stamp="$(date +%Y%m%d-%H%M%S)"
capture_serial="${ANDROID_SERIAL:-emulator-5554}"
capture_remote="/sdcard/Download/rea-${capture_name}-${capture_stamp}.mp4"
capture_local="$REA_PROJECT_ROOT/reference/captures/${capture_name}-${capture_stamp}.mp4"
mkdir -p "$REA_PROJECT_ROOT/reference/captures"
"$REA_ADB_PATH" -s "$capture_serial" shell screenrecord --time-limit "$capture_seconds" "$capture_remote"
"$REA_ADB_PATH" -s "$capture_serial" pull "$capture_remote" "$capture_local"
"$REA_ADB_PATH" -s "$capture_serial" shell rm "$capture_remote"
echo "$capture_local"

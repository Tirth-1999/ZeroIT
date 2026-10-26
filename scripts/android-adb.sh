#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
exec "$REA_ADB_PATH" -s emulator-5554 "$@"

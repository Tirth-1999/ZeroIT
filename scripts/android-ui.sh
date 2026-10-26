#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
exec python3 "$REA_PROJECT_ROOT/scripts/android-ui.py" "$@"

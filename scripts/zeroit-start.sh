#!/usr/bin/env bash
set -euo pipefail
# Keep the existing AVD and its saved ledger during the display rebrand.
exec bash "$(dirname "$0")/fairshare-start.sh" "$@"

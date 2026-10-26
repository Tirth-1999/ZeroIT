#!/usr/bin/env bash
set -euo pipefail
build_root="$(cd "$(dirname "$0")/.." && pwd)"
# Validate before loading SDK/Gradle environment. No opt-out for connected tests.
check_only=false
if [[ "${1:-}" == "--check-only" ]]; then check_only=true; shift; fi
if [[ "$#" -eq 0 ]]; then set -- assembleDebug; fi
python3 "$build_root/scripts/android-test-disposable.py" guard-build -- "$@"
if [[ "$check_only" == true ]]; then exit 0; fi
source "$build_root/scripts/android-env.sh"
export KONAN_DATA_DIR="$REA_PROJECT_ROOT/reference/konan"
export GRADLE_USER_HOME="$REA_PROJECT_ROOT/reference/gradle-home"
cd "$REA_PROJECT_ROOT"
exec ./gradlew "$@"

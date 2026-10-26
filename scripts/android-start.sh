#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
exec "$ANDROID_HOME/emulator/emulator" -avd rea-splitwise-api36 -port 5554 -no-snapshot-load -no-boot-anim -gpu swiftshader -feature -Vulkan -memory 3072 -no-audio "$@"

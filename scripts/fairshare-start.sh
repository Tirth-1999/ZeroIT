#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
exec "$ANDROID_HOME/emulator/emulator" -avd fairshare-api35 -port 5556 -no-snapshot-load -no-boot-anim -gpu swiftshader -feature -Vulkan -memory "${FAIRSHARE_ANDROID_MEMORY_MB:-2048}" -no-audio "$@"

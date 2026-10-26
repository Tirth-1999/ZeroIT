#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ios-env.sh"
cd "$mobile_root"
# Native linking needs more heap than the Android-only build on the first run.
exec ./gradlew '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' :shared:embedAndSignAppleFrameworkForXcode

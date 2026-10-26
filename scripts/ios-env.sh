#!/usr/bin/env bash
# Source from iOS scripts. Keep build caches local and do not change xcode-select globally.
mobile_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app/Contents/Developer ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
if [[ -x "$mobile_root/reference/tools/temurin21/Contents/Home/bin/java" ]]; then
    export JAVA_HOME="$mobile_root/reference/tools/temurin21/Contents/Home"
elif [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$mobile_root/reference/gradle-home}"
export KONAN_DATA_DIR="${KONAN_DATA_DIR:-$mobile_root/reference/konan}"

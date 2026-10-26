#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ios-env.sh"
if ! xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
    echo 'Full Xcode with the iOS SDK is required. Install and open Xcode, finish its setup, then rerun this script.' >&2
    exit 1
fi
cd "$mobile_root"
ios_configuration="${IOS_BUILD_CONFIGURATION:-Debug}"
case "$ios_configuration" in Debug|Release) ;; *) echo 'IOS_BUILD_CONFIGURATION must be Debug or Release.' >&2; exit 1 ;; esac
exec xcodebuild -project iosApp/iosApp.xcodeproj -scheme Fairshare -configuration "$ios_configuration" \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$mobile_root/reference/ios-derived-data" CODE_SIGNING_ALLOWED=NO build "$@"

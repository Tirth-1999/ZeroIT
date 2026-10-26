#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ios-env.sh"
cd "$mobile_root"
./gradlew '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8' :shared:iosSimulatorArm64Test
if [[ -n "${IOS_SIMULATOR_UDID:-}" ]]; then
    result_path="$mobile_root/reference/ios-ui-tests-$(date -u +%Y%m%dT%H%M%SZ).xcresult"
    xcodebuild -project iosApp/iosApp.xcodeproj -scheme Fairshare -configuration Debug \
        -destination "platform=iOS Simulator,id=$IOS_SIMULATOR_UDID" \
        -parallel-testing-enabled NO \
        -derivedDataPath "$mobile_root/reference/ios-derived-data" \
        -resultBundlePath "$result_path" CODE_SIGNING_ALLOWED=NO test
else
    echo 'Shared native tests completed. Set IOS_SIMULATOR_UDID to also run the iOS UI acceptance tests.'
fi

#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/ios-env.sh"
# Interactive previews use optimized native code; acceptance tests retain Debug isolation.
export IOS_BUILD_CONFIGURATION="${IOS_BUILD_CONFIGURATION:-Release}"
"$mobile_root/scripts/ios-build.sh"
simulator_id="${IOS_SIMULATOR_UDID:-}"
if [[ -z "$simulator_id" ]]; then
    simulator_id="$(xcrun simctl list devices available --json | python3 -c 'import json,re,sys; devices=[d for runtime,items in json.load(sys.stdin)["devices"].items() if ".iOS-" in runtime for d in items if re.search(r"SimDeviceType\.iPhone-(17|18)(-|$)", d.get("deviceTypeIdentifier", ""))]; devices.sort(key=lambda d:(d["state"]!="Booted", not d["deviceTypeIdentifier"].endswith("iPhone-17"), d["name"])); print(devices[0]["udid"] if devices else "")')"
fi
if [[ -z "$simulator_id" ]]; then
    echo 'No iPhone 17 or 18 simulator is available. Install a compatible iOS runtime and create an iPhone 17 or 18 device, or set IOS_SIMULATOR_UDID explicitly.' >&2
    exit 1
fi
if ! xcrun simctl list devices booted | rg -F "$simulator_id" >/dev/null; then xcrun simctl boot "$simulator_id"; fi
xcrun simctl bootstatus "$simulator_id" -b
if [[ -d "$DEVELOPER_DIR/Applications/Simulator.app" ]]; then
    open "$DEVELOPER_DIR/Applications/Simulator.app"
elif [[ -d "$DEVELOPER_DIR/../Applications/DeviceHub.app" ]]; then
    open "$DEVELOPER_DIR/../Applications/DeviceHub.app"
fi
xcrun simctl install "$simulator_id" "$mobile_root/reference/ios-derived-data/Build/Products/$IOS_BUILD_CONFIGURATION-iphonesimulator/Fairshare.app"
xcrun simctl launch "$simulator_id" org.fairshare.app

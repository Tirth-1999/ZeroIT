#!/usr/bin/env bash
set -euo pipefail
harness_root="$(cd "$(dirname "$0")" && pwd)"
project_root="$(cd "$harness_root/.." && pwd)"
# Host-only standalone build. JDK/cache are caller-owned; never invoke Android tasks.
# --offline is mandatory: missing dependencies fail instead of being downloaded.
exec "$project_root/gradlew" -p "$harness_root" --offline --no-daemon --console=plain conformance "$@"

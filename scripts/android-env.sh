#!/usr/bin/env bash
# Source this file from the project scripts; keep research state local.
REA_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$REA_PROJECT_ROOT/reference/tools/temurin21/Contents/Home"
export ANDROID_HOME="$REA_PROJECT_ROOT/reference/tools/android-sdk"
export ANDROID_USER_HOME="$REA_PROJECT_ROOT/reference/android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$REA_PROJECT_ROOT/reference/avd"
export REA_ADB_PATH="$ANDROID_HOME/platform-tools/adb"
export REA_JADX_MCP_JAR="$REA_PROJECT_ROOT/reference/tools/jadx-headless-mcp-0.7.1-all.jar"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

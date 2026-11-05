#!/usr/bin/env bash
set -euo pipefail

# Run from the repo root.
cd "$(dirname "$0")/../.."

ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  if [ -x "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
    ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
  else
    echo "adb not found. Set ADB=/path/to/adb." >&2
    exit 1
  fi
fi

if [ -z "${JAVA_HOME:-}" ] && [ -d "/d/Android Studio/jbr" ]; then
  export JAVA_HOME="/d/Android Studio/jbr"
fi

if ! "$ADB" devices | grep -q "[[:space:]]device$"; then
  echo "No device/emulator connected. Start one first." >&2
  "$ADB" devices
  exit 1
fi

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.imageedit.PipelineBenchmarkTest

"$ADB" logcat -d -s PipelineBenchmark:I | tail -60

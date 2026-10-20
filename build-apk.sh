#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    cat >&2 <<'MESSAGE'
Android SDK is not configured.

Install Android Studio or Android SDK command-line tools, and install:
  platforms;android-35
  build-tools;35.0.0

Then export ANDROID_HOME (or ANDROID_SDK_ROOT) and run ./build-apk.sh again.
See README.md for complete instructions.
MESSAGE
    exit 2
fi
./gradlew --no-daemon :app:assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk
[ -s "$apk" ] || { echo "Build finished but APK was not found: $apk" >&2; exit 1; }
cp "$apk" ./tsk-android-debug.apk
echo "APK ready: $(pwd)/tsk-android-debug.apk"

#!/bin/sh
# Run the on-device tests (app/src/androidTest) on a running emulator, and only there: Gradle
# removes the app and its data from whatever device it tested on.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/android-toolchain/sdk}"
# The running emulator, or the one named in ANDROID_SERIAL when there are several.
if [ -z "$ANDROID_SERIAL" ]; then
    running="$("$ANDROID_HOME/platform-tools/adb" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')"
    [ "$(echo "$running" | wc -w)" -le 1 ] || { echo "Several emulators are running; pick one with ANDROID_SERIAL=emulator-<port>." >&2; exit 1; }
    ANDROID_SERIAL="$running"
fi
case "$ANDROID_SERIAL" in
    emulator-*) ;;
    *) echo "Start an emulator first; these tests don't run on a phone." >&2; exit 1 ;;
esac
export ANDROID_SERIAL
status=0
./gradlew -q connectedDebugAndroidTest -Pemu "$@" || status=$?
./gradlew -q --stop >/dev/null 2>&1 || true
[ "$status" = 0 ] || exit "$status"
ls app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/*/ 2>/dev/null || true
echo "Tests passed on $ANDROID_SERIAL"

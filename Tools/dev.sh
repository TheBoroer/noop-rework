#!/bin/bash
# dev.sh — edit → see it on the device, automatically.
#
# Watches the Android app's source trees; on any change runs an incremental
# installDebug and cold-relaunches the app. Defaults to an emulator — boots
# one if none is running. Pass a serial to target a specific device (phone).
# Dependency-free (polling via `find -newer`, ~2s granularity) so it works
# without fswatch. Ctrl-C to stop.
#
# Usage:  Tools/dev.sh [device-serial]
#   device-serial — optional; passed to adb -s. Skips emulator handling.
#   NOOP_AVD      — env override for the AVD name (default: first listed).

set -u
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"

APP_ID="com.noop.whoop.rework.debug"
SERIAL="${1:-}"

emulator_bin() {
    command -v emulator 2>/dev/null && return
    local sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    [ -x "$sdk/emulator/emulator" ] && echo "$sdk/emulator/emulator"
}

running_emulator() {
    adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1; exit }'
}

if [ -z "$SERIAL" ]; then
    SERIAL="$(running_emulator)"
    if [ -z "$SERIAL" ]; then
        EMU="$(emulator_bin)"
        if [ -z "$EMU" ]; then
            echo "[dev] no emulator binary found (set ANDROID_HOME?)" >&2; exit 1
        fi
        AVD="${NOOP_AVD:-$("$EMU" -list-avds 2>/dev/null | head -1)}"
        if [ -z "$AVD" ]; then
            echo "[dev] no AVDs available — create one in Android Studio" >&2; exit 1
        fi
        echo "[dev] no emulator running — booting ${AVD}..."
        "$EMU" -avd "$AVD" -netdelay none -netspeed full >/dev/null 2>&1 &
        for _ in $(seq 1 60); do
            SERIAL="$(running_emulator)"
            [ -n "$SERIAL" ] && break
            sleep 2
        done
        if [ -z "$SERIAL" ]; then
            echo "[dev] emulator failed to appear in adb after 120s" >&2; exit 1
        fi
        echo "[dev] $SERIAL up — waiting for boot…"
        until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
            sleep 2
        done
        echo "[dev] boot complete"
    fi
fi
ADB=(adb -s "$SERIAL")

WATCH_DIRS=(android/app/src ui-common/src shared/src)
STAMP="$(mktemp /tmp/noop-devloop.XXXXXX)"

cleanup() { rm -f "$STAMP"; exit 0; }
trap cleanup INT TERM

changed() {
    find "${WATCH_DIRS[@]}" -type f \
        \( -name '*.kt' -o -name '*.xml' -o -name '*.kts' -o -name '*.sq' \) \
        -newer "$STAMP" 2>/dev/null | head -3
}

deploy() {
    local t0=$SECONDS
    # Stamp BEFORE building: edits made mid-build get picked up next cycle.
    touch "$STAMP"
    if (cd android && ./gradlew :app:installDebug -q --console=plain); then
        "${ADB[@]}" shell am force-stop "$APP_ID"
        # monkey launches whichever launcher alias is currently enabled.
        "${ADB[@]}" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        echo "[dev] deployed + relaunched in $((SECONDS - t0))s"
    else
        echo "[dev] BUILD FAILED — fix and save again (run ./gradlew :app:installDebug for details)"
    fi
}

echo "[dev] target: $SERIAL"
echo "[dev] watching: ${WATCH_DIRS[*]}"
echo "[dev] initial deploy…"
deploy

while true; do
    sleep 2
    CHANGES="$(changed)"
    [ -z "$CHANGES" ] && continue
    echo "[dev] change: $(echo "$CHANGES" | head -1 | sed 's|.*/||')"
    # Debounce: wait for a 2s quiet window so multi-file agent edits batch up.
    while true; do
        touch "$STAMP"
        sleep 2
        [ -z "$(changed)" ] && break
    done
    deploy
done

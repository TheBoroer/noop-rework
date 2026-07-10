#!/usr/bin/env bash
# Builds Shared.xcframework from the Kotlin shared module.
# Xcode builds need this to exist before xcodegen/xcodebuild; CI runs it in app-build.yml.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "(17|21)'; then
  for CAND in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
              "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
              "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [ -n "$CAND" ] && [ -x "$CAND/bin/java" ]; then export JAVA_HOME="$CAND"; break; fi
  done
fi
echo "JAVA_HOME=$JAVA_HOME"
CONFIG="${1:-Release}"
cd "$ROOT/android"
./gradlew ":shared:assembleShared${CONFIG}XCFramework" --console=plain
echo "Built: $ROOT/shared/build/XCFrameworks/$(echo "$CONFIG" | tr '[:upper:]' '[:lower:]')/Shared.xcframework"

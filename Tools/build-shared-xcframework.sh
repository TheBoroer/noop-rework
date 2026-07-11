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
echo "JAVA_HOME=${JAVA_HOME:-}"
if [ -z "${JAVA_HOME:-}" ] || ! [ -x "$JAVA_HOME/bin/java" ]; then
  echo "No JDK 17/21 found; install one or set JAVA_HOME" >&2
  exit 1
fi
CONFIG="${1:-Release}"
if [ "$CONFIG" != "Release" ]; then
  echo "Warning: Package.swift links the release/ output path, so a $CONFIG build is NOT picked up by SPM." >&2
fi
cd "$ROOT/android"
./gradlew ":shared:assembleShared${CONFIG}XCFramework" --console=plain
echo "Built: $ROOT/shared/build/XCFrameworks/$(echo "$CONFIG" | tr '[:upper:]' '[:lower:]')/Shared.xcframework"

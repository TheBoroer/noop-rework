#!/usr/bin/env bash
#
# update-altstore-source.sh — refresh altstore-source.json with a new iOS release.
#
# Run this LOCALLY at release time (like Tools/update-homebrew-cask.sh), right after the
# anonymized .ipa is built. It reads the real CFBundleVersion out of the IPA's Info.plist
# and the file's byte size, then prepends (or replaces, if the same version) the entry at
# apps[0].versions[0] — index 0 is "latest" per the AltStore source spec. The latest entry
# is also mirrored into the legacy app-level fields so older AltStore/SideStore parsers work.
#
# We deliberately do NOT do this from CI: a GitHub Actions job committing back to main would
# land a github-actions[bot] commit on the repo, which would show a non-NoopApp contributor.
# Doing it here keeps the only committer the project identity.
#
# Reimplements #178 by @RazvanRex under the project identity.
#
# Usage:
#   Tools/update-altstore-source.sh <version> <path-to-ipa> ["release one-liner"]
#   e.g. Tools/update-altstore-source.sh 2.6.3 dist/NOOP-v2.6.3-ios.ipa "Universal macOS build; iOS import fix."
#
set -euo pipefail

VERSION="${1:?usage: update-altstore-source.sh <version> <ipa> [desc]}"
IPA="${2:?usage: update-altstore-source.sh <version> <ipa> [desc]}"
DESC="${3:-"NOOP $VERSION. See the GitHub release notes for what changed."}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/altstore-source.json"
MIN_OS="17.0"

[ -f "$SRC" ] || { echo "✗ $SRC not found" >&2; exit 1; }
[ -f "$IPA" ] || { echo "✗ IPA not found: $IPA" >&2; exit 1; }
command -v jq >/dev/null || { echo "✗ jq is required" >&2; exit 1; }

# --- pull CFBundleVersion out of the IPA's (binary) Info.plist ---
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -qq "$IPA" -d "$TMP"
PLIST="$(find "$TMP/Payload" -maxdepth 2 -name Info.plist | head -1)"
[ -n "$PLIST" ] || { echo "✗ no Payload/*.app/Info.plist in IPA" >&2; exit 1; }
BUILD="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$PLIST")"
SHORT="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$PLIST")"
if [ "$SHORT" != "$VERSION" ]; then
  echo "⚠ IPA CFBundleShortVersionString=$SHORT but you passed $VERSION (continuing with $VERSION)" >&2
fi

SIZE="$(stat -f%z "$IPA")"
DATE="$(date -u +%Y-%m-%d)"
URL="https://github.com/NoopApp/noop/releases/download/v${VERSION}/NOOP-v${VERSION}-ios.ipa"

echo "→ $VERSION (build $BUILD), ${SIZE} bytes, $DATE"

# --- prepend-or-replace the versions[0] entry, and mirror legacy app-level fields ---
jq --arg v "$VERSION" --arg b "$BUILD" --arg d "$DATE" --arg desc "$DESC" \
   --arg url "$URL" --argjson size "$SIZE" --arg min "$MIN_OS" '
  ( {version:$v, buildVersion:$b, date:$d, localizedDescription:$desc,
     downloadURL:$url, size:$size, minOSVersion:$min} ) as $entry
  | .apps[0].versions = ([ $entry ] + ( .apps[0].versions | map(select(.version != $v)) ))
  # legacy top-level mirror of "latest" for older parsers
  | .apps[0].version          = $v
  | .apps[0].buildVersion     = $b
  | .apps[0].versionDate      = $d
  | .apps[0].versionDescription = $desc
  | .apps[0].downloadURL      = $url
  | .apps[0].size             = $size
  | .apps[0].minOSVersion     = $min
' "$SRC" > "$SRC.tmp" && mv "$SRC.tmp" "$SRC"

# validate it's still well-formed JSON
jq empty "$SRC" && echo "✓ altstore-source.json updated for $VERSION"

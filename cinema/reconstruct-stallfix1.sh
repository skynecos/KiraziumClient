#!/usr/bin/env bash
set -euo pipefail

BASE_URL="https://raw.githubusercontent.com/skynecos/KiraziumLauncher/dreamdisplays-android9-build/artifacts/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android9.jar"
BASE_SHA256="9525319933e1fb629cd151b37326e7dbba88286a1db3d742dba7277d4070f935"
BASE_SIZE="23437862"
PATCH_SHA256="9ab36322303a8582d76c083bd34a777728c0057eb4deed22319e95b1a48ae9bd"
PATCH_SIZE="599883"
TARGET_SHA256="918872694b9fe437b6c412dda287e717d33b654a1bf8885af0ceea0ed38caab1"
TARGET_SIZE="23595235"
TARGET="app_pojavlauncher/src/main/assets/kirazium/mods/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar"

WORK="${RUNNER_TEMP:-/tmp}/kirazium-cinema"
rm -rf "$WORK"
mkdir -p "$WORK" "$(dirname "$TARGET")"

BASE="$WORK/android9.jar"
PATCH_B64="$WORK/stallfix1.patch.b64"
PATCH="$WORK/stallfix1.bsdiff"

curl -fL --retry 4 --retry-all-errors "$BASE_URL" -o "$BASE"
test "$(stat -c%s "$BASE")" = "$BASE_SIZE"
echo "$BASE_SHA256  $BASE" | sha256sum -c -

cat cinema/patches/stallfix1.patch.b64.part-* > "$PATCH_B64"
base64 --decode "$PATCH_B64" > "$PATCH"
test "$(stat -c%s "$PATCH")" = "$PATCH_SIZE"
echo "$PATCH_SHA256  $PATCH" | sha256sum -c -

rm -f "$TARGET"
bspatch "$BASE" "$TARGET" "$PATCH"
test "$(stat -c%s "$TARGET")" = "$TARGET_SIZE"
echo "$TARGET_SHA256  $TARGET" | sha256sum -c -
unzip -tqq "$TARGET"

echo "Verified exact Kirazium DreamDisplays stallfix1: $TARGET_SHA256"

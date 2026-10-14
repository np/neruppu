#!/usr/bin/env bash
# Builds the gomobile AAR and installs it into the repo-local Maven
# repository (age-encryption/maven) that settings.gradle.kts declares.
#
# A local Maven repo rather than files("…aar"): AGP 8 refuses direct
# .aar file dependencies in library modules such as :data.
#
# Requires: Go >= 1.24, Android SDK + NDK.
#   export ANDROID_HOME=$HOME/Android/Sdk
#   export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
#   ./age-encryption/build-aar.sh
set -euo pipefail

cd "$(dirname "$0")"

GROUP_PATH=org/havenapp/neruppu
ARTIFACT=neruppu-age
VERSION=1.0.0
MIN_SDK=26   # matches minSdk of :data / :app

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an installed NDK}"

# gomobile/gobind at the x/mobile version pinned in go.mod.
GOBIN_DIR="$(pwd)/.gobin"
GOBIN="$GOBIN_DIR" go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$GOBIN_DIR:$PATH"

# No `gomobile init`: bind does not need it, and it would install
# gobind@latest, which may require a newer Go than go.mod pins.

out_dir="maven/$GROUP_PATH/$ARTIFACT/$VERSION"
mkdir -p "$out_dir"

gomobile bind \
    -target=android/arm64,android/arm,android/amd64 \
    -androidapi "$MIN_SDK" \
    -trimpath -ldflags="-s -w" \
    -o "$out_dir/$ARTIFACT-$VERSION.aar" \
    .

rm -f "$out_dir/$ARTIFACT-$VERSION-sources.jar"

cat > "$out_dir/$ARTIFACT-$VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP_PATH//\//.}</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <version>$VERSION</version>
  <packaging>aar</packaging>
</project>
POM

echo "Installed $out_dir/$ARTIFACT-$VERSION.aar"

#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.9
BOOT="${HOME}/.gradle/sc2-tmg-bootstrap"
GRADLE_HOME="${BOOT}/gradle-${GRADLE_VERSION}"
GRADLE_EXE="${GRADLE_HOME}/bin/gradle"
if [ ! -x "$GRADLE_EXE" ]; then
  mkdir -p "$BOOT"
  ZIP="$BOOT/gradle-${GRADLE_VERSION}-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail -o "$ZIP" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  else
    echo "curl or wget is required for first Gradle bootstrap." >&2
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$BOOT"
  else
    echo "unzip is required for first Gradle bootstrap." >&2
    exit 1
  fi
fi
exec "$GRADLE_EXE" "$@"

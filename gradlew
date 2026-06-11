#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_VERSION="9.5.1"
GRADLE_DIR="$ROOT_DIR/.gradle-dist/gradle-$GRADLE_VERSION"
GRADLE_ZIP="$ROOT_DIR/.gradle-dist/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  mkdir -p "$ROOT_DIR/.gradle-dist"
  if [ ! -f "$GRADLE_ZIP" ]; then
    curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
  fi
  unzip -q "$GRADLE_ZIP" -d "$ROOT_DIR/.gradle-dist"
fi

exec "$GRADLE_DIR/bin/gradle" "$@"

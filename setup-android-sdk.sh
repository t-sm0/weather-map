#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$ROOT_DIR/.android-sdk"
TOOLS_DIR="$SDK_DIR/cmdline-tools/latest"
TOOLS_ZIP="$ROOT_DIR/.android-sdk/commandlinetools-linux-latest.zip"

mkdir -p "$SDK_DIR/cmdline-tools"

if [ ! -x "$TOOLS_DIR/bin/sdkmanager" ]; then
  curl -fL "https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip" -o "$TOOLS_ZIP"
  rm -rf "$SDK_DIR/cmdline-tools/latest" "$SDK_DIR/cmdline-tools/cmdline-tools"
  unzip -q "$TOOLS_ZIP" -d "$SDK_DIR/cmdline-tools"
  mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$TOOLS_DIR"
fi

yes | "$TOOLS_DIR/bin/sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null || true
"$TOOLS_DIR/bin/sdkmanager" --sdk_root="$SDK_DIR" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.1.0"

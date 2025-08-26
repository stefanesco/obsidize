#!/bin/bash
set -euo pipefail

# Compute SHA256 values and URLs for Homebrew formula update
TAG_NAME="${1:-${GITHUB_REF_NAME:-}}"
VERSION="${TAG_NAME#v}"
REPO="${2:-${GITHUB_REPOSITORY:-}}"

if [[ -z "$TAG_NAME" || -z "$REPO" ]]; then
  echo "❌ Usage: $0 <tag_name> <repo>"
  exit 1
fi

echo "VERSION=$VERSION" >> "$GITHUB_ENV"
echo "REPO=$REPO" >> "$GITHUB_ENV"
echo "TAG_NAME=$TAG_NAME" >> "$GITHUB_ENV"

echo "Artifacts tree:"
ls -R artifacts || true

ARM_TGZ="$(find artifacts -type f -name "obsidize-${VERSION}-macos-aarch64.tar.gz" -print -quit || true)"
AMD_TGZ="$(find artifacts -type f -name "obsidize-${VERSION}-macos-x64.tar.gz" -print -quit || true)"

if [[ -z "$ARM_TGZ" || -z "$AMD_TGZ" ]]; then
  echo "Missing macOS jlink tarballs. Found:"
  find artifacts -type f -name "obsidize-*.tar.gz" -maxdepth 2 -print
  exit 1
fi

ARM_SHA="$(shasum -a 256 "$ARM_TGZ" | awk '{print $1}')"
AMD_SHA="$(shasum -a 256 "$AMD_TGZ" | awk '{print $1}')"

echo "ARM64_SHA=$ARM_SHA" >> "$GITHUB_ENV"
echo "AMD64_SHA=$AMD_SHA" >> "$GITHUB_ENV"
echo "ARM64_URL=https://github.com/$REPO/releases/download/$TAG_NAME/$(basename "$ARM_TGZ")" >> "$GITHUB_ENV"
echo "AMD64_URL=https://github.com/$REPO/releases/download/$TAG_NAME/$(basename "$AMD_TGZ")" >> "$GITHUB_ENV"

LINUX_TGZ="$(find artifacts -type f -name "obsidize-${VERSION}-linux-*.tar.gz" -print -quit || true)"
if [[ -n "$LINUX_TGZ" ]]; then
  LINUX_SHA="$(shasum -a 256 "$LINUX_TGZ" | awk '{print $1}')"
  echo "LINUX_SHA=$LINUX_SHA" >> "$GITHUB_ENV"
  echo "LINUX_URL=https://github.com/$REPO/releases/download/$TAG_NAME/$(basename "$LINUX_TGZ")" >> "$GITHUB_ENV"
fi
#!/bin/bash
set -euo pipefail

# Prepare comprehensive platform tarball with all artifacts
VERSION="$(echo "${RELEASE_VERSION}" | sed 's/^v//')"
RELEASE_DIR="target/release/${VERSION}"
PLATFORM_ID="${1:-}"
OS_ID="${2:-}"

if [[ -z "$PLATFORM_ID" || -z "$OS_ID" ]]; then
  echo "❌ Usage: $0 <platform_id> <os_id>"
  exit 1
fi

echo "📦 Creating comprehensive tarball for ${OS_ID} (${PLATFORM_ID})"
STAGE_DIR="$(mktemp -d)/obsidize-${VERSION}-${PLATFORM_ID}"
mkdir -p "${STAGE_DIR}/bin"

# Always include standalone JAR
if [[ -f "${RELEASE_DIR}/obsidize-standalone.jar" ]]; then
  cp "${RELEASE_DIR}/obsidize-standalone.jar" "${STAGE_DIR}/"
  echo "✅ Added standalone JAR"
else
  echo "❌ Missing standalone JAR at ${RELEASE_DIR}/obsidize-standalone.jar"
  exit 1
fi

# Include native image for macOS
if [[ "${OS_ID}" == "macOS" && -f "${RELEASE_DIR}/obsidize-native" ]]; then
  install -m 0755 "${RELEASE_DIR}/obsidize-native" "${STAGE_DIR}/bin/obsidize-native"
  echo "✅ Added native image for macOS"
fi

# Extract and include jlink distribution for all platforms
JLINK_ARCHIVE="$(ls "${RELEASE_DIR}"/obsidize-"${VERSION}"-${PLATFORM_ID}.* 2>/dev/null | head -n1 || true)"
if [[ -n "$JLINK_ARCHIVE" ]]; then
  echo "✅ Found jlink archive: $JLINK_ARCHIVE"
  TEMP_EXTRACT="$(mktemp -d)"
  
  if [[ "$JLINK_ARCHIVE" == *.zip ]]; then
    unzip -q "$JLINK_ARCHIVE" -d "$TEMP_EXTRACT"
  else
    tar -xf "$JLINK_ARCHIVE" -C "$TEMP_EXTRACT"
  fi
  
  # Find the extracted jlink directory
  JLINK_DIR="$(find "$TEMP_EXTRACT" -maxdepth 1 -type d -name "obsidize-*" | head -n1)"
  if [[ -n "$JLINK_DIR" ]]; then
    # Copy jlink contents to our stage directory
    cp -r "$JLINK_DIR"/* "${STAGE_DIR}/"
    echo "✅ Added jlink distribution"
  else
    echo "❌ Could not find jlink directory in archive"
    exit 1
  fi
  
  rm -rf "$TEMP_EXTRACT"
else
  echo "❌ No jlink archive found for ${PLATFORM_ID}"
  exit 1
fi

# Create final tarball
TAR_NAME="obsidize-${VERSION}-${PLATFORM_ID}.tar.gz"
tar -C "$(dirname "${STAGE_DIR}")" -czf "$TAR_NAME" "$(basename "${STAGE_DIR}")"
shasum -a 256 "$TAR_NAME" | awk '{print $1}' > "${TAR_NAME}.sha256"

echo "✅ Created comprehensive tarball: $TAR_NAME"
echo "Contents:"
# Avoid SIGPIPE when head closes pipe before tar finishes
(tar -tzf "$TAR_NAME" | head -20) 2>/dev/null || echo "Archive contents preview unavailable"
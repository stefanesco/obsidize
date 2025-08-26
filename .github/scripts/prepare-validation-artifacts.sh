#!/bin/bash
set -euo pipefail

# Prepare downloaded artifacts for e2e validation testing
echo "📦 Setting up comprehensive artifacts for e2e validation..."
ls -la validation-artifacts/ || echo "No artifacts found"

# Create target structure expected by e2e tests
mkdir -p target/release/test-validation

# Extract the comprehensive platform tarball
PLATFORM_TAR="$(find validation-artifacts -name "obsidize-*.tar.gz" | head -n1)"
if [[ -n "$PLATFORM_TAR" ]]; then
  echo "✅ Found platform tarball: $PLATFORM_TAR"
  tar -xzf "$PLATFORM_TAR" -C target/release/test-validation/ --strip-components=1
  echo "✅ Extracted comprehensive platform artifacts"
else
  echo "❌ No platform tarball found"
  exit 1
fi

echo "Contents of target/release/test-validation/:"
ls -la target/release/test-validation/ || echo "Empty"

# Verify expected artifacts are present
if [[ -f target/release/test-validation/obsidize-standalone.jar ]]; then
  echo "✅ Found standalone JAR"
else
  echo "❌ Missing standalone JAR"
  exit 1
fi

if [[ -f target/release/test-validation/bin/obsidize ]]; then
  echo "✅ Found jlink launcher script"
else
  echo "❌ Missing jlink launcher script"
  exit 1
fi

# Check for native binary on macOS
OS_ID="${1:-}"
if [[ "$OS_ID" == "macOS" ]]; then
  if [[ -f target/release/test-validation/bin/obsidize-native ]]; then
    echo "✅ Found native binary for macOS"
  else
    echo "❌ Missing native binary for macOS"
    exit 1
  fi
fi
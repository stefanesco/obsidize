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

# Platform-specific validation based on our build strategy
OS_ID="${1:-}"
ARCH="${2:-}"

if [[ "$OS_ID" == "macOS" ]]; then
  if [[ "$ARCH" == "arm64" ]]; then
    # macOS ARM64: Should have native executable at bin/obsidize (no jlink components)
    echo "ℹ️  Validating macOS ARM64 native-only package..."
    if [[ -f target/release/test-validation/bin/obsidize ]]; then
      echo "✅ Found native executable for macOS ARM64"
    else
      echo "❌ Missing native executable at bin/obsidize for macOS ARM64"
      exit 1
    fi
  else
    # macOS x86: Should have jlink launcher at bin/obsidize (no native binary)
    echo "ℹ️  Validating macOS x86 jlink runtime package..."
    if [[ -f target/release/test-validation/bin/obsidize ]]; then
      echo "✅ Found jlink launcher for macOS x86"
      # Verify it's actually a JLink package by checking for JRE components
      if [[ -d target/release/test-validation/lib ]]; then
        echo "✅ Confirmed jlink runtime structure (lib/ directory present)"
      else
        echo "❌ Missing jlink runtime structure for macOS x86"
        exit 1
      fi
    else
      echo "❌ Missing jlink launcher at bin/obsidize for macOS x86"
      exit 1
    fi
  fi
else
  # Linux: Should have jlink launcher (same validation as macOS x86)
  echo "ℹ️  Validating Linux jlink runtime package..."
  if [[ -d target/release/test-validation/lib ]]; then
    echo "✅ Confirmed jlink runtime structure for Linux"
  else
    echo "❌ Missing jlink runtime structure for Linux"
    exit 1
  fi
fi
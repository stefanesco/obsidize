#!/bin/bash
set -euo pipefail

# Prepare downloaded artifacts for e2e validation testing
echo "📦 Setting up platform-specific artifacts for e2e validation..."
ls -la validation-artifacts/ || echo "No artifacts found"

# Create target structure expected by e2e tests
mkdir -p target/release/test-validation

# Extract the platform-specific tarball
PLATFORM_TAR="$(find validation-artifacts -name "obsidize-*.tar.gz" | head -n1)"
if [[ -n "$PLATFORM_TAR" ]]; then
  echo "✅ Found platform tarball: $PLATFORM_TAR"
  tar -xzf "$PLATFORM_TAR" -C target/release/test-validation/ --strip-components=1
  echo "✅ Extracted platform-specific artifacts"
else
  echo "❌ No platform tarball found"
  exit 1
fi

echo "Contents of target/release/test-validation/:"
ls -la target/release/test-validation/ || echo "Empty"

# Set up universal JAR for e2e tests (downloaded separately)
if [[ -f "obsidize.jar" ]]; then
  # Place universal JAR in the standard location expected by e2e tests  
  mkdir -p target/release
  cp obsidize.jar target/release/obsidize-standalone.jar
  echo "✅ Placed universal JAR for e2e validation"
else
  echo "⚠️  Universal JAR not found - e2e JAR tests will be skipped"
fi

# Set up platform-specific executables for e2e tests
if [[ -f target/release/test-validation/bin/obsidize ]]; then
  # Copy platform executable to where e2e tests expect to find it
  mkdir -p target/release
  if [[ -x target/release/test-validation/bin/obsidize ]]; then
    # For native executables, copy directly
    if [[ ! -d target/release/test-validation/lib ]]; then
      cp target/release/test-validation/bin/obsidize target/release/obsidize-native
      echo "✅ Set up native executable for e2e validation"
    else
      # For jlink packages, create a symlink to the jlink structure
      ln -sf "$(pwd)/target/release/test-validation" target/release/jlink-package
      echo "✅ Set up jlink package for e2e validation" 
    fi
  fi
  echo "✅ Found platform launcher script"
else
  echo "❌ Missing platform launcher script"
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
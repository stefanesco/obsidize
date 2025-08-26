#!/bin/bash
set -euo pipefail

# Validate version format and set environment
VERSION="${1:-${GITHUB_REF_NAME:-}}"

if [[ -z "$VERSION" ]]; then
  echo "❌ Version not provided"
  echo "Usage: $0 <version>"
  exit 1
fi

# Security: Validate version format to prevent command injection
if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
  echo "❌ Invalid version format: $VERSION"
  echo "Expected format: v1.2.3 or v1.2.3-alpha.1"
  exit 1
fi

echo "✅ Version format validated: $VERSION"
echo "RELEASE_VERSION=$VERSION" >> "$GITHUB_ENV"
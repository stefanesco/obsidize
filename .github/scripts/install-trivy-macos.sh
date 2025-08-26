#!/bin/bash
set -euo pipefail

# Install Trivy on macOS via Homebrew
echo "🔒 Installing Trivy via Homebrew..."
brew install aquasecurity/trivy/trivy
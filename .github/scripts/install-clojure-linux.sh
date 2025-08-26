#!/bin/bash
set -euo pipefail

# Install Clojure CLI on Linux with security validation
echo "🔒 Downloading Clojure installer with security validation..."
curl -fsSL "${CLOJURE_DOWNLOAD_URL}/linux-install-${CLOJURE_CLI_VERSION}.sh" -o clojure.sh

# Security: Validate downloaded script checksum in a fail-safe way
if ! echo "${CLOJURE_INSTALL_SHA256}  clojure.sh" | sha256sum -c --status; then
  echo "❌ Clojure installer checksum validation failed"
  exit 1
fi
echo "✅ Clojure installer checksum validated"

chmod +x clojure.sh
sudo ./clojure.sh
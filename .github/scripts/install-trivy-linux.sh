#!/bin/bash
set -euo pipefail

# Install Trivy on Linux with GPG key validation
echo "🔒 Installing Trivy with GPG key validation..."
sudo apt-get update
sudo apt-get install -y wget gnupg lsb-release

# Security: Download and validate GPG key and Key ID
wget -qO trivy.key "${TRIVY_PUBLIC_KEY_URL}"
if ! file trivy.key | grep -q "PGP public key"; then
  echo "❌ Downloaded file is not a valid PGP public key"
  exit 1
fi

KEYINFO=$(gpg --show-keys --keyid-format LONG trivy.key)
echo "$KEYINFO"
if ! echo "$KEYINFO" | grep -q "${TRIVY_GPG_KEY_ID}"; then
  echo "❌ Trivy GPG key ID mismatch. Expected ${TRIVY_GPG_KEY_ID}"
  exit 1
fi
echo "✅ Trivy GPG public key validated (Key ID: ${TRIVY_GPG_KEY_ID})"

# Import validated key
sudo gpg --dearmor -o /usr/share/keyrings/trivy.gpg < trivy.key
rm trivy.key

echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" \
  | sudo tee /etc/apt/sources.list.d/trivy.list >/dev/null
sudo apt-get update && sudo apt-get install -y trivy
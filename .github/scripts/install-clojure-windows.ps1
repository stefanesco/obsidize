#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

Write-Output "🔒 Installing Clojure CLI via Chocolatey with version validation..."

choco install clojure --version $env:CHOCOLATEY_CLOJURE_VERSION -y --no-progress

# Verify exact installed version via Chocolatey
$pkg = choco list --local-only --exact clojure --limit-output
if (-not $pkg) {
    throw "❌ Clojure package not found after installation."
}
$installedVersion = ($pkg -split '\|')[1]
if ($installedVersion -ne $env:CHOCOLATEY_CLOJURE_VERSION) {
    throw "❌ Clojure version mismatch. Expected: $env:CHOCOLATEY_CLOJURE_VERSION, Got: $installedVersion"
}
Write-Output "✅ Clojure CLI $installedVersion installed and validated"
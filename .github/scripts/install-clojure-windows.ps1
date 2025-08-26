#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

Write-Output "🔒 Installing Clojure CLI via Chocolatey with version validation..."

# Install Clojure via Chocolatey
choco install clojure --version $env:CHOCOLATEY_CLOJURE_VERSION -y --no-progress

# Refresh environment variables to ensure PATH includes Clojure
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

# Wait a moment for installation to complete
Start-Sleep -Seconds 2

# Verify Clojure CLI is accessible
try {
    $clojureVersion = clojure -version 2>&1
    Write-Output "✅ Clojure CLI accessible: $clojureVersion"
} catch {
    Write-Error "❌ Clojure CLI not accessible after installation. PATH may need manual refresh."
    Write-Output "Current PATH: $env:Path"
    throw
}

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
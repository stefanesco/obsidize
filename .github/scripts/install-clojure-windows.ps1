#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

Write-Output "🔒 Installing Clojure CLI via Chocolatey with version validation..."

# Install Clojure via Chocolatey
choco install clojure --version $env:CHOCOLATEY_CLOJURE_VERSION -y --no-progress

# Add Clojure installation path to current session PATH
$ClojurePath = "C:\ProgramData\chocolatey\lib\clojure\tools"
$env:Path = "$ClojurePath;$env:Path"

# Also refresh from registry in case other paths were added
$MachinePath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
$UserPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
$env:Path = "$ClojurePath;$MachinePath;$UserPath"

Write-Output "Updated PATH includes Clojure tools directory: $ClojurePath"

# Wait a moment for installation to complete
Start-Sleep -Seconds 2

# Verify Clojure CLI is accessible
try {
    $clojureVersion = & clojure -version 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Output "✅ Clojure CLI accessible: $clojureVersion"
    } else {
        throw "clojure command returned exit code $LASTEXITCODE"
    }
} catch {
    Write-Error "❌ Clojure CLI not accessible after installation."
    Write-Output "Clojure tools directory exists: $(Test-Path $ClojurePath)"
    Write-Output "Contents of tools directory:"
    if (Test-Path $ClojurePath) { Get-ChildItem $ClojurePath }
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
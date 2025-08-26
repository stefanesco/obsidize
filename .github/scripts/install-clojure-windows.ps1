#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

Write-Output "🔒 Installing Clojure CLI via Chocolatey with version validation..."

# Install Clojure via Chocolatey
choco install clojure --version $env:CHOCOLATEY_CLOJURE_VERSION -y --no-progress

# Add Clojure installation paths to current session PATH
$ClojureToolsPath = "C:\ProgramData\chocolatey\lib\clojure\tools"
$ChocolateyBinPath = "C:\ProgramData\chocolatey\bin" 
$env:Path = "$ClojureToolsPath;$ChocolateyBinPath;$env:Path"

# Also refresh from registry in case other paths were added
refreshenv
$MachinePath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
$UserPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
$env:Path = "$ClojureToolsPath;$ChocolateyBinPath;$MachinePath;$UserPath"

Write-Output "Updated PATH includes:"
Write-Output "  - Clojure tools: $ClojureToolsPath"
Write-Output "  - Chocolatey bin: $ChocolateyBinPath"

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
    Write-Output "Debug information:"
    Write-Output "  - Clojure tools directory exists: $(Test-Path $ClojureToolsPath)"
    Write-Output "  - Chocolatey bin directory exists: $(Test-Path $ChocolateyBinPath)"
    Write-Output "  - Contents of tools directory:"
    if (Test-Path $ClojureToolsPath) { Get-ChildItem $ClojureToolsPath | Format-Table Name, Length }
    Write-Output "  - Contents of bin directory:"
    if (Test-Path $ChocolateyBinPath) { Get-ChildItem $ChocolateyBinPath | Where-Object Name -like "*clojure*" | Format-Table Name, Length }
    Write-Output "  - Current PATH: $env:Path"
    
    # Try to find clojure.exe specifically
    $clojureExe = Get-Command clojure -ErrorAction SilentlyContinue
    if ($clojureExe) {
        Write-Output "  - Found clojure at: $($clojureExe.Source)"
    } else {
        Write-Output "  - clojure.exe not found in PATH"
    }
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
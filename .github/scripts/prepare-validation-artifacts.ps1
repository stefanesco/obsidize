#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory=$true)]
    [string]$OsId
)

$ErrorActionPreference = 'Stop'

# Prepare downloaded artifacts for e2e validation testing
Write-Output "📦 Setting up comprehensive artifacts for e2e validation..."
if (Test-Path "validation-artifacts") {
    Get-ChildItem "validation-artifacts" | Format-Table Name, Length
} else {
    Write-Output "No artifacts found"
}

# Create target structure expected by e2e tests
New-Item -ItemType Directory -Path "target/release/test-validation" -Force

# Extract the comprehensive platform tarball
if (Test-Path "validation-artifacts") {
    $PlatformTar = Get-ChildItem "validation-artifacts" -Filter "obsidize-*.tar.gz" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($PlatformTar) {
        Write-Output "✅ Found platform tarball: $($PlatformTar.FullName)"
        tar -xzf $PlatformTar.FullName -C "target/release/test-validation/" --strip-components=1
        Write-Output "✅ Extracted comprehensive platform artifacts"
    } else {
        Write-Warning "⚠️  No platform tarball found - Windows builds are experimental"
        Write-Output "📝 Creating minimal structure for compatibility testing..."
        # Create basic structure for any tests that might still work
        New-Item -ItemType Directory -Path "target/release/test-validation/bin" -Force
        exit 0
    }
} else {
    Write-Warning "⚠️  No validation artifacts directory found - Windows builds are experimental" 
    Write-Output "📝 Creating minimal structure for compatibility testing..."
    New-Item -ItemType Directory -Path "target/release/test-validation/bin" -Force
    exit 0
}

Write-Output "Contents of target/release/test-validation/:"
if (Test-Path "target/release/test-validation") {
    Get-ChildItem "target/release/test-validation" | Format-Table Name, Length
} else {
    Write-Output "Empty"
}

# Verify expected artifacts are present (Windows builds are experimental)
if (Test-Path "target/release/test-validation/obsidize-standalone.jar") {
    Write-Output "✅ Found standalone JAR"
} else {
    Write-Warning "⚠️  Missing standalone JAR - Windows builds are experimental"
}

if (Test-Path "target/release/test-validation/bin/obsidize") {
    Write-Output "✅ Found jlink launcher script"
} else {
    Write-Warning "⚠️  Missing jlink launcher script - Windows builds are experimental" 
}

# Check for native binary on macOS
if ($OsId -eq "macOS") {
    if (Test-Path "target/release/test-validation/bin/obsidize-native") {
        Write-Output "✅ Found native binary for macOS"
    } else {
        Write-Error "❌ Missing native binary for macOS"
        exit 1
    }
}
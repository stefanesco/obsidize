#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory=$true)]
    [string]$OsId
)

$ErrorActionPreference = 'Stop'

# Prepare downloaded artifacts for e2e validation testing
Write-Output "📦 Setting up platform-specific artifacts for e2e validation..."
if (Test-Path "validation-artifacts") {
    Get-ChildItem "validation-artifacts" | Format-Table Name, Length
} else {
    Write-Output "No artifacts found"
}

# Create target structure expected by e2e tests
New-Item -ItemType Directory -Path "target/release/test-validation" -Force

# Extract the platform-specific tarball
if (Test-Path "validation-artifacts") {
    $PlatformTar = Get-ChildItem "validation-artifacts" -Filter "obsidize-*.tar.gz" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($PlatformTar) {
        Write-Output "✅ Found platform tarball: $($PlatformTar.FullName)"
        tar -xzf $PlatformTar.FullName -C "target/release/test-validation/" --strip-components=1
        Write-Output "✅ Extracted platform-specific artifacts"
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

# Set up universal JAR for e2e tests (downloaded separately)
if (Test-Path "obsidize.jar") {
    # Place universal JAR in the standard location expected by e2e tests
    New-Item -ItemType Directory -Path "target/release" -Force
    Copy-Item "obsidize.jar" "target/release/obsidize-standalone.jar"
    Write-Output "✅ Placed universal JAR for e2e validation"
} else {
    Write-Warning "⚠️  Universal JAR not found - e2e JAR tests will be skipped"
}

# Platform-specific executables (Windows experimental)
if (Test-Path "target/release/test-validation/bin/obsidize.cmd") {
    Write-Output "✅ Found Windows launcher script"
} else {
    Write-Warning "⚠️  Missing Windows launcher script - Windows builds are experimental"
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
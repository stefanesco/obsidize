#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory=$true)]
    [string]$PlatformId,
    [Parameter(Mandatory=$true)]
    [string]$OsId
)

$ErrorActionPreference = 'Stop'

# Prepare comprehensive platform tarball with all artifacts
$Version = $env:RELEASE_VERSION -replace '^v', ''
$ReleaseDir = "target/release/$Version"

Write-Output "📦 Creating platform-specific package for $OsId ($PlatformId)"
$TempDir = New-TemporaryFile | ForEach-Object { Remove-Item $_; New-Item -ItemType Directory -Path $_ }
$StageDir = Join-Path $TempDir "obsidize-$Version-$PlatformId"
$BinDir = Join-Path $StageDir "bin"
New-Item -ItemType Directory -Path $StageDir, $BinDir -Force

# NOTE: Universal JAR is distributed separately - platform packages only contain platform-specific binaries

# Windows currently only supports JAR-only builds (experimental status)
if ($OsId -eq "Windows") {
    # For Windows, we don't build native or jlink packages yet
    # The universal JAR is available separately for Windows users
    Write-Output "ℹ️  Windows package contains JAR reference only (experimental)"
    
    # Create a simple launcher script for Windows
    $LauncherPath = Join-Path $BinDir "obsidize.cmd"
    @"
@echo off
REM Windows launcher for obsidize (requires Java 21+)
REM Download obsidize.jar from GitHub releases
if exist "%~dp0..\obsidize.jar" (
    java -jar "%~dp0..\obsidize.jar" %*
) else (
    echo Error: obsidize.jar not found
    echo Please download obsidize.jar from GitHub releases
    exit /b 1
)
"@ | Out-File -FilePath $LauncherPath -Encoding ascii
    
    Write-Output "✅ Added Windows launcher script"
} else {
    Write-Error "❌ PowerShell script called for non-Windows platform: $OsId"
    exit 1
}

# Create final tarball using tar (available on Windows 10+)
$TarName = "obsidize-$Version-$PlatformId.tar.gz"
$StageParent = Split-Path $StageDir
$StageName = Split-Path $StageDir -Leaf

tar -C $StageParent -czf $TarName $StageName

# Generate SHA256 checksum
$Hash = Get-FileHash $TarName -Algorithm SHA256
$Hash.Hash | Out-File "$TarName.sha256" -Encoding ascii

Write-Output "✅ Created platform-specific package: $TarName"
Write-Output "Contents:"
tar -tzf $TarName | Select-Object -First 20

# Cleanup
Remove-Item $TempDir -Recurse -Force
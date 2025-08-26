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

Write-Output "📦 Creating comprehensive tarball for $OsId ($PlatformId)"
$TempDir = New-TemporaryFile | ForEach-Object { Remove-Item $_; New-Item -ItemType Directory -Path $_ }
$StageDir = Join-Path $TempDir "obsidize-$Version-$PlatformId"
$BinDir = Join-Path $StageDir "bin"
New-Item -ItemType Directory -Path $StageDir, $BinDir -Force

# Always include standalone JAR
$JarPath = Join-Path $ReleaseDir "obsidize-standalone.jar"
if (Test-Path $JarPath) {
    Copy-Item $JarPath $StageDir
    Write-Output "✅ Added standalone JAR"
} else {
    Write-Error "❌ Missing standalone JAR at $JarPath"
    exit 1
}

# Include native image for macOS
if ($OsId -eq "macOS") {
    $NativePath = Join-Path $ReleaseDir "obsidize-native"
    if (Test-Path $NativePath) {
        Copy-Item $NativePath (Join-Path $BinDir "obsidize-native")
        Write-Output "✅ Added native image for macOS"
    }
}

# Extract and include jlink distribution for all platforms
$JlinkArchive = Get-ChildItem "$ReleaseDir/obsidize-$Version-$PlatformId.*" | Select-Object -First 1
if ($JlinkArchive) {
    Write-Output "✅ Found jlink archive: $($JlinkArchive.FullName)"
    $TempExtract = New-TemporaryFile | ForEach-Object { Remove-Item $_; New-Item -ItemType Directory -Path $_ }
    
    if ($JlinkArchive.Extension -eq '.zip') {
        Expand-Archive -Path $JlinkArchive.FullName -DestinationPath $TempExtract -Force
    } else {
        # Use tar command on Windows (available in Windows 10+)
        tar -xf $JlinkArchive.FullName -C $TempExtract
    }
    
    # Find the extracted jlink directory
    $JlinkDir = Get-ChildItem $TempExtract -Directory | Where-Object { $_.Name -like "obsidize-*" } | Select-Object -First 1
    if ($JlinkDir) {
        # Copy jlink contents to our stage directory
        Get-ChildItem $JlinkDir.FullName | Copy-Item -Destination $StageDir -Recurse -Force
        Write-Output "✅ Added jlink distribution"
    } else {
        Write-Error "❌ Could not find jlink directory in archive"
        exit 1
    }
    
    Remove-Item $TempExtract -Recurse -Force
} else {
    Write-Error "❌ No jlink archive found for $PlatformId"
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

Write-Output "✅ Created comprehensive tarball: $TarName"
Write-Output "Contents:"
tar -tzf $TarName | Select-Object -First 20

# Cleanup
Remove-Item $TempDir -Recurse -Force
#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory=$true)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Version)) {
    Write-Error "❌ Version not provided"
    exit 1
}

# Security: Validate version format to prevent command injection
if ($Version -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$') {
    Write-Error "❌ Invalid version format: $Version"
    Write-Output "Expected format: v1.2.3 or v1.2.3-alpha.1"
    exit 1
}

Write-Output "✅ Version format validated: $Version"
"RELEASE_VERSION=$Version" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8
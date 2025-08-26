#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

Write-Output "🔒 Installing Clojure CLI via Chocolatey with version validation..."

# Install Clojure via Chocolatey
choco install clojure --version $env:CHOCOLATEY_CLOJURE_VERSION -y --no-progress

# Find the actual Clojure installation paths
$ClojureLibPath = "C:\ProgramData\chocolatey\lib\clojure"
$ClojureToolsPath = "$ClojureLibPath\tools"
$ChocolateyBinPath = "C:\ProgramData\chocolatey\bin"

Write-Output "Searching for Clojure executable..."
Write-Output "  - Checking tools path: $ClojureToolsPath"
Write-Output "  - Checking bin path: $ChocolateyBinPath"

# Instead of refreshenv (which doesn't work in GitHub Actions), use the Chocolatey module approach
$ChocolateyInstall = $env:ChocolateyInstall
if ($ChocolateyInstall -and (Test-Path "$ChocolateyInstall\helpers\chocolateyProfile.psm1")) {
    Write-Output "  - Loading Chocolatey profile module..."
    Import-Module "$ChocolateyInstall\helpers\chocolateyProfile.psm1" -Force
    Update-SessionEnvironment
} else {
    Write-Output "  - Chocolatey profile not available, manually updating PATH..."
    # Manually refresh PATH from registry
    $MachinePath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
    $UserPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$MachinePath;$UserPath"
}

# Ensure our Clojure paths are in the session PATH
$env:Path = "$ClojureToolsPath;$ChocolateyBinPath;$env:Path"

Write-Output "Final PATH includes Clojure directories"

# Wait a moment for installation to complete
Start-Sleep -Seconds 2

# First, let's explore what was actually installed
Write-Output "🔍 Examining Clojure installation structure..."

# Check all possible locations for clojure executables
$PossiblePaths = @(
    $ClojureToolsPath,
    $ChocolateyBinPath,
    "$ClojureLibPath\bin",
    "$env:ProgramFiles\Clojure\bin",
    "$env:ProgramFiles(x86)\Clojure\bin"
)

$ClojureExecutables = @()
foreach ($Path in $PossiblePaths) {
    if (Test-Path $Path) {
        Write-Output "  - Checking path: $Path"
        $exes = Get-ChildItem $Path -Filter "clojure*" -File -ErrorAction SilentlyContinue
        if ($exes) {
            Write-Output "    Found executables: $($exes.Name -join ', ')"
            $ClojureExecutables += $exes | ForEach-Object { $_.FullName }
        } else {
            Write-Output "    No clojure executables found"
        }
    } else {
        Write-Output "  - Path does not exist: $Path"
    }
}

# If we found executables, add their directories to PATH
if ($ClojureExecutables) {
    Write-Output "🔧 Found Clojure executables, updating PATH..."
    $UniquePaths = $ClojureExecutables | ForEach-Object { Split-Path $_ } | Select-Object -Unique
    foreach ($Path in $UniquePaths) {
        if ($env:Path -notlike "*$Path*") {
            $env:Path = "$Path;$env:Path"
            Write-Output "  - Added to PATH: $Path"
        }
    }
}

# Try direct execution of found executables first
$WorkingExecutable = $null
foreach ($exe in $ClojureExecutables) {
    Write-Output "🧪 Testing executable: $exe"
    try {
        $result = & $exe -version 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Output "  ✅ Works! Version: $result"
            $WorkingExecutable = $exe
            break
        } else {
            Write-Output "  ❌ Failed with exit code: $LASTEXITCODE"
        }
    } catch {
        Write-Output "  ❌ Exception: $($_.Exception.Message)"
    }
}

# Now verify Clojure CLI is accessible via PATH
try {
    Write-Output "🧪 Testing 'clojure' command via PATH..."
    $clojureVersion = & clojure -version 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Output "✅ Clojure CLI accessible via PATH: $clojureVersion"
    } else {
        throw "clojure command returned exit code $LASTEXITCODE"
    }
} catch {
    Write-Error "❌ Clojure CLI not accessible after installation."
    Write-Output "📊 Final diagnostic information:"
    Write-Output "  - Working executable found: $($WorkingExecutable -ne $null)"
    if ($WorkingExecutable) { Write-Output "  - Working executable path: $WorkingExecutable" }
    Write-Output "  - Current PATH: $env:Path"
    
    # Final attempt with Get-Command
    $clojureCmd = Get-Command clojure -ErrorAction SilentlyContinue
    if ($clojureCmd) {
        Write-Output "  - Get-Command found clojure at: $($clojureCmd.Source)"
        try {
            $testResult = & $clojureCmd.Source -version 2>&1
            Write-Output "  - Direct execution result: $testResult (exit code: $LASTEXITCODE)"
        } catch {
            Write-Output "  - Direct execution failed: $($_.Exception.Message)"
        }
    } else {
        Write-Output "  - Get-Command did not find clojure executable"
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

# Export PATH to GitHub Actions environment for subsequent steps
if ($WorkingExecutable) {
    $ExecutableDir = Split-Path $WorkingExecutable
    Write-Output "📝 Exporting to GitHub PATH: $ExecutableDir"
    "$ExecutableDir" | Out-File -FilePath $env:GITHUB_PATH -Append -Encoding utf8
} else {
    # Fallback to standard paths
    Write-Output "📝 Exporting standard paths to GitHub PATH"
    "$ClojureToolsPath" | Out-File -FilePath $env:GITHUB_PATH -Append -Encoding utf8
    "$ChocolateyBinPath" | Out-File -FilePath $env:GITHUB_PATH -Append -Encoding utf8
}
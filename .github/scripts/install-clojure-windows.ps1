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

# First, let's see what was actually installed
Write-Output "📂 Examining actual installation contents..."
if (Test-Path $ClojureToolsPath) {
    Write-Output "  - Contents of tools directory:"
    Get-ChildItem $ClojureToolsPath -Recurse | ForEach-Object {
        Write-Output "    $($_.FullName) $(if ($_.PSIsContainer) {'[DIR]'} else {"[$($_.Length) bytes]"})"
    }
}

# Check all possible locations for clojure executables
$PossiblePaths = @(
    $ClojureToolsPath,
    "$ClojureToolsPath\bin",
    "$ClojureToolsPath\ClojureTools",
    $ChocolateyBinPath,
    "$ClojureLibPath\bin",
    "$env:ProgramFiles\Clojure\bin",
    "$env:ProgramFiles(x86)\Clojure\bin"
)

$ClojureExecutables = @()
foreach ($Path in $PossiblePaths) {
    if (Test-Path $Path) {
        Write-Output "  - Checking path: $Path"
        # Look specifically for clojure and clj scripts (the standard Clojure CLI tools)
        $clojureFiles = @()
        $clojureFiles += Get-ChildItem $Path -Filter "clojure*" -File -ErrorAction SilentlyContinue
        $clojureFiles += Get-ChildItem $Path -Filter "clj*" -File -ErrorAction SilentlyContinue
        
        $allFiles = Get-ChildItem $Path -File -ErrorAction SilentlyContinue
        
        if ($clojureFiles) {
            Write-Output "    Found Clojure tools: $($clojureFiles.Name -join ', ')"
            $ClojureExecutables += $clojureFiles | ForEach-Object { $_.FullName }
        } else {
            Write-Output "    No clojure/clj scripts found (total files: $($allFiles.Count))"
            if ($allFiles.Count -gt 0 -and $allFiles.Count -le 15) {
                Write-Output "    Files present: $($allFiles.Name -join ', ')"
            } elseif ($allFiles.Count -gt 15) {
                Write-Output "    Sample files: $($allFiles[0..4].Name -join ', ')... (and $($allFiles.Count - 5) more)"
            }
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

# Test the found Clojure tools
$WorkingExecutable = $null
Write-Output "🔍 Testing found Clojure tools: $($ClojureExecutables.Count) files"

# Prioritize 'clojure' over 'clj' and exact matches over partial matches
$PrioritizedExecutables = @()
$PrioritizedExecutables += $ClojureExecutables | Where-Object { $_.Name -eq "clojure" -or $_.Name -eq "clojure.bat" -or $_.Name -eq "clojure.cmd" -or $_.Name -eq "clojure.ps1" }
$PrioritizedExecutables += $ClojureExecutables | Where-Object { $_.Name -eq "clj" -or $_.Name -eq "clj.bat" -or $_.Name -eq "clj.cmd" -or $_.Name -eq "clj.ps1" }
$PrioritizedExecutables += $ClojureExecutables | Where-Object { $_ -notin $PrioritizedExecutables }

foreach ($exe in $PrioritizedExecutables) {
    Write-Output "🧪 Testing: $($exe) ($(Split-Path -Leaf $exe))"
    try {
        # For Clojure CLI, -version should work
        $result = & $exe -version 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Output "  ✅ SUCCESS with -version! Output: $result"
            $WorkingExecutable = $exe
            break
        } else {
            Write-Output "  ❌ -version failed (exit: $LASTEXITCODE), output: $result"
        }
    } catch {
        Write-Output "  ❌ Exception: $($_.Exception.Message)"
        # Try different approaches for scripts
        if ($exe -match '\.(bat|cmd|ps1)$') {
            try {
                Write-Output "  🔄 Trying PowerShell execution for script..."
                $result = powershell -Command "& '$exe' -version" 2>&1
                if ($LASTEXITCODE -eq 0) {
                    Write-Output "  ✅ SUCCESS via PowerShell! Output: $result"
                    $WorkingExecutable = $exe
                    break
                }
            } catch {
                Write-Output "  ❌ PowerShell execution also failed: $($_.Exception.Message)"
            }
        }
    }
}

# If no clojure executables found, check if we have java and can find clojure jars
if (-not $WorkingExecutable) {
    Write-Output "🔍 No clojure executables found, looking for Java + Clojure JARs..."
    $javaExe = Get-Command java -ErrorAction SilentlyContinue
    if ($javaExe) {
        Write-Output "  ✅ Found Java: $($javaExe.Source)"
        # Look for clojure jar files
        $clojureJars = Get-ChildItem $ClojureToolsPath -Filter "*.jar" -Recurse -ErrorAction SilentlyContinue
        if ($clojureJars) {
            Write-Output "  ✅ Found Clojure JARs: $($clojureJars.Name -join ', ')"
            # We'll handle this case by creating a wrapper script
        }
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
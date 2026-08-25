# DropAim — set up JAVA_HOME and adb for command-line builds on Windows.
#
# Android Studio bundles its own JDK and SDK but puts neither on PATH, so
# `gradlew` reports "JAVA_HOME is not set" and `adb` is not recognised.
#
# For this window only:
#   . .\tools\dev-env.ps1
#
# To set it permanently for your user (do this once, then reopen PowerShell):
#   . .\tools\dev-env.ps1 -Persist

param([switch]$Persist)

# ── JDK: Android Studio's bundled runtime ─────────────────────────────
$jdkCandidates = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",      # Studio 2022.2+
    "$env:ProgramFiles\Android\Android Studio\jre",      # older Studio
    "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
)
$jdk = $jdkCandidates | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1

if ($jdk) {
    $env:JAVA_HOME = $jdk
    $env:Path = "$jdk\bin;$env:Path"
    Write-Host "JAVA_HOME = $jdk" -ForegroundColor Green
} else {
    Write-Host "No bundled JDK found. Open Android Studio > Settings > Build > Build Tools > Gradle" -ForegroundColor Red
    Write-Host "and note the 'Gradle JDK' path, then set JAVA_HOME to it by hand." -ForegroundColor Red
}

# ── SDK platform-tools: adb ───────────────────────────────────────────
$sdkCandidates = @(
    "$env:LOCALAPPDATA\Android\Sdk",
    "$env:USERPROFILE\AppData\Local\Android\Sdk",
    "C:\Android\Sdk"
)
$sdk = $sdkCandidates | Where-Object { Test-Path (Join-Path $_ 'platform-tools\adb.exe') } | Select-Object -First 1

if ($sdk) {
    $env:ANDROID_HOME = $sdk
    $env:Path = "$sdk\platform-tools;$env:Path"
    Write-Host "ANDROID_HOME = $sdk" -ForegroundColor Green
} else {
    Write-Host "No Android SDK found. In Android Studio: Settings > Languages & Frameworks >" -ForegroundColor Red
    Write-Host "Android SDK, install 'Android SDK Platform-Tools', then re-run this." -ForegroundColor Red
}

if ($Persist -and $jdk) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
    if ($sdk) {
        [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $add = "$sdk\platform-tools"
        if ($userPath -notlike "*$add*") {
            [Environment]::SetEnvironmentVariable('Path', "$userPath;$add", 'User')
        }
    }
    Write-Host "`nSaved for your user account. Close and reopen PowerShell." -ForegroundColor Cyan
}

Write-Host "`nCheck:" -ForegroundColor DarkGray
if (Get-Command java -ErrorAction SilentlyContinue) { java -version 2>&1 | Select-Object -First 1 }
if (Get-Command adb  -ErrorAction SilentlyContinue) { adb version  2>&1 | Select-Object -First 1 }

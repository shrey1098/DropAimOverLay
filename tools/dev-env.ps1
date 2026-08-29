

param([switch]$Persist)

$jdkCandidates = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio\jre",
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

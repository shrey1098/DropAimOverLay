# DropAim — video/network diagnostic collector
#
# Run from Windows PowerShell with the GCS connected by USB and USB debugging on.
# Writes dropaim-diag.txt next to this script. Send that file back.
#
#   cd tools
#   .\collect-diag.ps1
#
# If PowerShell refuses to run it:
#   powershell -ExecutionPolicy Bypass -File .\collect-diag.ps1

$ErrorActionPreference = 'Continue'
$out = Join-Path $PSScriptRoot 'dropaim-diag.txt'
$cam = '192.168.144.108'
$pkg = 'com.dropaim.app'

# adb is usually not on PATH after an Android Studio install — find it.
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\Android Studio\platform-tools\adb.exe",
        "C:\Android\Sdk\platform-tools\adb.exe"
    )
    $found = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($found) {
        $env:Path = (Split-Path $found) + ';' + $env:Path
        Write-Host "Using adb at $found" -ForegroundColor DarkGray
    } else {
        Write-Host "adb not found. Install Android Studio's platform-tools, or add adb.exe to PATH." -ForegroundColor Red
        exit 1
    }
}

function Section($title, $cmd) {
    "`n===== $title =====" | Out-File -Append -Encoding utf8 $out
    try   { (Invoke-Expression $cmd 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out }
    catch { "FAILED: $_" | Out-File -Append -Encoding utf8 $out }
}

"DropAim diagnostic — $(Get-Date -Format s)" | Out-File -Encoding utf8 $out

Section 'adb devices'        'adb devices -l'
Section 'app version'        "adb shell dumpsys package $pkg | Select-String -Pattern 'versionName|firstInstallTime|lastUpdateTime'"

# --- what networks does the GCS actually have? -----------------------------
Section 'ip addr'            'adb shell ip addr'
Section 'ip route'           'adb shell ip route'
# ARP table: reveals which devices the GCS has genuinely exchanged traffic
# with. If the camera is on a different IP than we assume, it shows up here.
Section 'ip neigh (ARP)'     'adb shell ip neigh'
Section 'default network'    'adb shell dumpsys connectivity | Select-String -Pattern "Active default network|NetworkAgentInfo" | Select-Object -First 25'

# --- can the GCS reach the camera at all? ----------------------------------
# NOTE: no /dev/tcp port checks here. That is a bash feature and Android's
# shell is mksh/toybox, which does not implement it — every port would have
# come back "shut" whether it was open or not. The app does the TCP probing
# itself in Java (NetDiag) and logs the result; see the logcat section.
Section "ping $cam"          "adb shell ping -c 4 -W 2 $cam"

# --- what the app itself reports -------------------------------------------
"`n===== restarting app and capturing 60 s of log =====" | Out-File -Append -Encoding utf8 $out
adb logcat -c
adb shell am force-stop $pkg
adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 60
Section 'logcat (app)'       'adb logcat -d -s VideoPipe:V NetDiag:V MainActivity:V Mavlink:V WebServer:V WebApp:V'

"`nWrote $out"
Write-Host "`nDone. Send this file: $out" -ForegroundColor Green

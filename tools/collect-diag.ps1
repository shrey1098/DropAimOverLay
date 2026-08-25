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
Section "ping $cam"          "adb shell ping -c 4 -W 2 $cam"
Section 'TCP 554'            "adb shell ""(echo > /dev/tcp/$cam/554) 2>&1 && echo OPEN554 || echo SHUT554"""
Section 'TCP 555'            "adb shell ""(echo > /dev/tcp/$cam/555) 2>&1 && echo OPEN555 || echo SHUT555"""
Section 'TCP 8554'           "adb shell ""(echo > /dev/tcp/$cam/8554) 2>&1 && echo OPEN8554 || echo SHUT8554"""
Section 'HTTP 80 (web UI)'   "adb shell ""(echo > /dev/tcp/$cam/80) 2>&1 && echo OPEN80 || echo SHUT80"""

# --- what the app itself reports -------------------------------------------
"`n===== restarting app and capturing 60 s of log =====" | Out-File -Append -Encoding utf8 $out
adb logcat -c
adb shell am force-stop $pkg
adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 60
Section 'logcat (app)'       'adb logcat -d -s VideoPipe:V NetDiag:V MainActivity:V Mavlink:V WebServer:V WebApp:V'

"`nWrote $out"
Write-Host "`nDone. Send this file: $out" -ForegroundColor Green

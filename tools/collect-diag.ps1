

$ErrorActionPreference = 'Continue'
$out = Join-Path $PSScriptRoot 'dropaim-diag.txt'
$cam = '192.168.144.108'
$pkg = 'com.dropaim.app'

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

Section 'ip addr'            'adb shell ip addr'
Section 'ip route'           'adb shell ip route'

Section 'ip neigh (ARP)'     'adb shell ip neigh'
Section 'default network'    'adb shell dumpsys connectivity | Select-String -Pattern "Active default network|NetworkAgentInfo" | Select-Object -First 25'

Section "ping $cam"          "adb shell ping -c 4 -W 2 $cam"

"`n===== restarting app and capturing 60 s of log =====" | Out-File -Append -Encoding utf8 $out
adb logcat -c
adb shell am force-stop $pkg
adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 60
Section 'logcat (app)'       'adb logcat -d -s VideoPipe:V NetDiag:V MainActivity:V Mavlink:V WebServer:V WebApp:V'

"`nWrote $out"
Write-Host "`nDone. Send this file: $out" -ForegroundColor Green

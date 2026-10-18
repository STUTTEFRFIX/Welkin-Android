# Boot a headless emulator, install the APK, exercise it, and capture screenshots.
param(
    [string]$Apk = 'D:\01-工作区\welkin-apk\Welkin-1.0.3.apk',
    [string]$Avd = 'welkin-test'
)
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$SDK    = 'D:\MobileGL-build\android-sdk'
$EMU    = Join-Path $SDK 'emulator\emulator.exe'
$ADB    = Join-Path $SDK 'platform-tools\adb.exe'
$AVDMGR = Join-Path $SDK 'cmdline-tools\latest\bin\avdmanager.bat'
$Work   = 'D:\welkin-build\emu'
$Shots  = 'D:\01-工作区\welkin-apk\shots'
$Image  = 'system-images;android-34;default;x86_64'

New-Item -ItemType Directory -Force -Path $Work, $Shots | Out-Null
$env:ANDROID_HOME = $SDK
$env:ANDROID_SDK_ROOT = $SDK

function Invoke-Adb { param([string[]]$Arguments) & $ADB @Arguments 2>&1 | Out-String }
function Shot { param([string]$Name) & $ADB exec-out screencap -p > (Join-Path $Shots $Name); Write-Host "      captured $Name" }
function Wait-Emulator {
    param([int]$TimeoutSeconds = 420)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    & $ADB start-server | Out-Null
    while ((Get-Date) -lt $deadline) {
        $devices = (& $ADB devices) -join "`n"
        if ($devices -match 'emulator-\d+\s+device') {
            $boot = (& $ADB shell getprop sys.boot_completed 2>&1) -join ''
            if ($boot -match '1') { return $true }
        }
        Start-Sleep -Seconds 5
    }
    return $false
}

# 1. Create the AVD if needed.
$avdList = (& $AVDMGR list avd 2>&1) -join "`n"
if ($avdList -notmatch [regex]::Escape("Name: $Avd")) {
    Write-Host "[avd] creating $Avd ..."
    cmd /c "echo no| `"$AVDMGR`" create avd -n $Avd -k `"$Image`" -d pixel_5 --force" 2>&1 | Out-String
} else {
    Write-Host "[avd] reusing $Avd"
}

# 2. Start the emulator headless with software GPU (WebGL via SwiftShader).
& $ADB kill-server | Out-Null
$emuArgs = @(
    '-avd', $Avd,
    '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot',
    '-gpu', 'swiftshader_indirect',
    '-memory', '2048', '-cores', '4',
    '-port', '5554'
)
Write-Host '[emu] starting ...'
$emuProcess = Start-Process -FilePath $EMU -ArgumentList $emuArgs -PassThru -RedirectStandardOutput (Join-Path $Work 'emulator.out.log') -RedirectStandardError (Join-Path $Work 'emulator.err.log')
Start-Sleep -Seconds 5

if (-not (Wait-Emulator -TimeoutSeconds 480)) {
    Write-Host '[emu] BOOT TIMEOUT'
    Get-Content (Join-Path $Work 'emulator.err.log') -Tail 40 -ErrorAction SilentlyContinue
    exit 2
}
Write-Host '[emu] booted'
& $ADB shell settings put global window_animation_scale 0 | Out-Null
& $ADB shell settings put global transition_animation_scale 0 | Out-Null
& $ADB shell settings put global animator_duration_scale 0 | Out-Null

# 3. Install and launch the app.
Write-Host "[app] installing $Apk ..."
$install = Invoke-Adb @('install', '-r', '-g', $Apk)
Write-Host $install

& $ADB logcat -c | Out-Null
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', 'com.welkin.archive/.MainActivity') | Out-Null
Start-Sleep -Seconds 14
Shot 'emu-01-launch.png'

# 4. Interactions: swipe the glass archive, then open the article index.
Invoke-Adb @('shell', 'input', 'swipe', '200', '900', '200', '500', '250') | Out-Null
Start-Sleep -Seconds 3
Shot 'emu-02-swipe.png'

$size = (Invoke-Adb @('shell', 'wm', 'size')) -join ''
Write-Host "      display: $size"
Invoke-Adb @('shell', 'input', 'tap', '540', '2010') | Out-Null
Start-Sleep -Seconds 3
Shot 'emu-03-archive.png'

# 5. Collect logs and evidence.
& $ADB logcat -d -v time 2>&1 | Out-File (Join-Path $Work 'logcat.txt') -Encoding UTF8
$appLog = (& $ADB logcat -d 2>&1) | Select-String -Pattern 'welkin|chromium|WebView|AndroidRuntime|FATAL' | Select-Object -First 40
$appLog | ForEach-Object { $_.Line } | Out-File (Join-Path $Work 'applog.txt') -Encoding UTF8
Write-Host '--- app log ---'
$appLog | ForEach-Object { $_.Line }

Stop-Process -Id $emuProcess.Id -Force -ErrorAction SilentlyContinue
Write-Host '[emu] stopped'


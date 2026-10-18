# Build the Welkin APK with an installed Android SDK, without Gradle.
# aapt2 (a native tool) cannot open paths containing non-ASCII characters, so the
# project is staged into an ASCII work directory and the result is copied back.
#
# Environment overrides (all optional):
#   ANDROID_SDK_HOME  Android SDK root            (default: auto-detect)
#   WELKIN_BUILD_DIR  ASCII scratch build folder   (default: %TEMP%\welkin-build)
#   JAVA_HOME         JDK 17 installation          (default: $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root        = Split-Path -Parent $MyInvocation.MyCommand.Path   # .../welkin-apk/android
$Project     = Split-Path -Parent $Root

$Work = if ($env:WELKIN_BUILD_DIR) { $env:WELKIN_BUILD_DIR } else { Join-Path $env:TEMP 'welkin-build' }
$SDK  = if ($env:ANDROID_SDK_HOME) { $env:ANDROID_SDK_HOME }
        elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
        elseif (Test-Path 'D:\MobileGL-build\android-sdk') { 'D:\MobileGL-build\android-sdk' }
        else { throw 'Set ANDROID_SDK_HOME (or ANDROID_HOME) to your Android SDK path.' }

# Prefer the newest build-tools / platform, but keep the API 35 platform when present.
$BUILD_TOOLS_DIR = Get-ChildItem (Join-Path $SDK 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name) } -Descending | Select-Object -First 1
if (-not $BUILD_TOOLS_DIR) { throw "No build-tools found under $SDK\build-tools" }
$BUILD_TOOLS = $BUILD_TOOLS_DIR.FullName
$PLATFORM_DIR = if (Test-Path (Join-Path $SDK 'platforms\android-35')) { Join-Path $SDK 'platforms\android-35' }
                else { (Get-ChildItem (Join-Path $SDK 'platforms') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName }
$PLATFORM  = Join-Path $PLATFORM_DIR 'android.jar'
$JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME.TrimEnd('\') } else { throw 'Set JAVA_HOME to a JDK 17 installation.' }
$JAVAC       = Join-Path $JAVA_HOME 'bin\javac.exe'
$KEYTOOL     = Join-Path $JAVA_HOME 'bin\keytool.exe'
$AAPT2       = Join-Path $BUILD_TOOLS 'aapt2.exe'
$D8          = Join-Path $BUILD_TOOLS 'd8.bat'
$ZIPALIGN    = Join-Path $BUILD_TOOLS 'zipalign.exe'
$APKSIGNER   = Join-Path $BUILD_TOOLS 'apksigner.bat'

$AppMain   = Join-Path $Root 'app\src\main'
$Site      = Join-Path $Project 'site\public'
$Build     = Join-Path $Work 'build'
$Keystore  = Join-Path $Work 'welkin-release.keystore'
$StorePass = 'welkin-archive'
$KeyAlias  = 'welkin'
$OutApk    = Join-Path $Project 'Welkin-1.0.3.apk'

foreach ($tool in @($AAPT2, $D8, $ZIPALIGN, $APKSIGNER, $JAVAC, $PLATFORM)) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}
if (-not (Test-Path (Join-Path $Site 'index.html'))) { throw "Generated site missing at $Site (run hexo generate first)." }

Write-Host '[1/8] Staging project into an ASCII-path workspace ...'
if (Test-Path (Join-Path $Work 'app')) { Remove-Item (Join-Path $Work 'app') -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $Work 'app'), $Build, (Join-Path $Build 'flat'), (Join-Path $Build 'classes'), (Join-Path $Build 'dex') | Out-Null
Copy-Item -Path (Join-Path $AppMain 'res') -Destination (Join-Path $Work 'app\res') -Recurse -Force
Copy-Item -Path (Join-Path $AppMain 'java') -Destination (Join-Path $Work 'app\java') -Recurse -Force
Copy-Item -Path (Join-Path $AppMain 'AndroidManifest.xml') -Destination (Join-Path $Work 'app\AndroidManifest.xml') -Force
$ResDir   = Join-Path $Work 'app\res'
$Manifest = Join-Path $Work 'app\AndroidManifest.xml'
$SrcDir   = Join-Path $Work 'app\java'

Write-Host '[2/8] Staging static site into assets/www ...'
$www = Join-Path $Work 'app\assets\www'
New-Item -ItemType Directory -Force -Path $www | Out-Null
Copy-Item -Path (Join-Path $Site '*') -Destination $www -Recurse -Force
$assetCount = (Get-ChildItem $www -Recurse -File).Count
$assetSize = [math]::Round(((Get-ChildItem $www -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host "      $assetCount files, $assetSize MB"

Write-Host '[3/8] Compiling and linking resources ...'
Add-Type -AssemblyName System.IO.Compression.FileSystem
& $AAPT2 compile --dir $ResDir -o (Join-Path $Build 'flat\res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
$flatDir = Join-Path $Build 'flat\res'
if (Test-Path $flatDir) { Remove-Item $flatDir -Recurse -Force }
[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $Build 'flat\res.zip'), $flatDir)
$flats = Get-ChildItem $flatDir -Recurse -Filter *.flat | ForEach-Object { $_.FullName }
if (-not $flats) { throw 'aapt2 compile produced no .flat resources' }
& $AAPT2 link `
    --manifest $Manifest `
    -o (Join-Path $Build 'base.apk') `
    -I $PLATFORM `
    --min-sdk-version 24 `
    --target-sdk-version 34 `
    --version-code 4 `
    --version-name 1.0.3 `
    @flats
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
# Assets are added separately: aapt2 on Windows writes asset entry names with
# backslashes ("assets/www\2026\..."), which Android's AssetManager cannot open.

Write-Host '[4/8] Compiling Java sources ...'
$sources = Get-ChildItem $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
& $JAVAC -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:none `
    -bootclasspath $PLATFORM -classpath $PLATFORM `
    -d (Join-Path $Build 'classes') @sources 2>&1 | ForEach-Object { if ($_ -notmatch 'warning') { Write-Host "      $_" } }
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[5/8] Dexing with d8 ...'
$classFiles = Get-ChildItem (Join-Path $Build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $D8 --release --min-api 24 --lib $PLATFORM --output (Join-Path $Build 'dex') @classFiles 2>&1 | ForEach-Object { Write-Host "      $_" }
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[6/8] Packaging dex and assets into the APK ...'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = Join-Path $Build 'unsigned.apk'
Copy-Item (Join-Path $Build 'base.apk') $apkPath -Force

function Add-ZipFile($archive, $entryName, $sourcePath, $level) {
    $entry = $archive.CreateEntry($entryName, $level)
    $stream = $entry.Open()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($sourcePath)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}

$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
try {
    # classes.dex stays uncompressed so older runtimes can map it directly.
    Add-ZipFile $zip 'classes.dex' (Join-Path $Build 'dex\classes.dex') ([System.IO.Compression.CompressionLevel]::NoCompression)
    $staged = Get-ChildItem $www -Recurse -File
    foreach ($file in $staged) {
        $relative = $file.FullName.Substring($www.Length + 1).Replace('\', '/')
        Add-ZipFile $zip ("assets/www/" + $relative) $file.FullName ([System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $zip.Dispose() }
Write-Host "      packed $($staged.Count) site files + classes.dex"

Write-Host '[7/8] Aligning and signing ...'
& $ZIPALIGN -p -f 4 $apkPath (Join-Path $Build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path $Keystore)) {
    # Reuse the project's signing identity so later builds can upgrade in place.
    $seed = Join-Path $Root 'welkin-release.keystore'
    if (Test-Path $seed) {
        Copy-Item $seed $Keystore -Force
    } else {
        & $KEYTOOL -genkeypair -v -keystore $Keystore -storepass $StorePass -keypass $StorePass `
            -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 `
            -dname 'CN=Welkin Archive, OU=Mobile, O=Welkin, L=Shanghai, ST=Shanghai, C=CN' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
        Copy-Item $Keystore $seed -Force
    }
}
& $APKSIGNER sign --ks $Keystore --ks-pass "pass:$StorePass" --ks-key-alias $KeyAlias `
    --key-pass "pass:$StorePass" --min-sdk-version 24 `
    --out $OutApk (Join-Path $Build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Host '[8/8] Verifying ...'
& $APKSIGNER verify --verbose --print-certs $OutApk | Select-Object -First 14
& $AAPT2 dump badging $OutApk | Select-Object -First 6

# Guard against the Windows aapt2 backslash asset bug and other packaging slips.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$checkZip = [System.IO.Compression.ZipFile]::OpenRead($OutApk)
try {
    $assetEntries = @($checkZip.Entries | Where-Object { $_.FullName -like 'assets/*' })
    $badNames = @($assetEntries | Where-Object { $_.FullName.Contains('\') })
    if ($badNames.Count -gt 0) {
        throw ("asset entries contain backslashes and would be unreadable on Android: " + $badNames[0].FullName)
    }
    $expected = (Get-ChildItem $www -Recurse -File).Count
    if ($assetEntries.Count -ne $expected) {
        throw "asset count mismatch: packed $($assetEntries.Count), expected $expected"
    }
    if (-not ($assetEntries | Where-Object { $_.FullName -eq 'assets/www/index.html' })) {
        throw 'assets/www/index.html is missing from the APK'
    }
    if (-not ($assetEntries | Where-Object { $_.FullName -eq 'assets/www/assets/welkin/posts.js' })) {
        throw 'assets/www/assets/welkin/posts.js is missing from the APK'
    }
    Write-Host ("      assets ok: {0} entries, / separators, index.html present" -f $assetEntries.Count)
} finally { $checkZip.Dispose() }

$info = Get-Item $OutApk
Write-Host ""
Write-Host ("APK:        {0}" -f $info.FullName)
Write-Host ("Size:       {0:N2} MB" -f ($info.Length / 1MB))
Write-Host ("SHA256:     {0}" -f (Get-FileHash $OutApk -Algorithm SHA256).Hash)




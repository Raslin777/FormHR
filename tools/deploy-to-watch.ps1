param(
    [Parameter(Mandatory = $true)]
    [string]$Device
)

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
if (-not (Test-Path $apk)) { throw "Build the APK first: .\gradlew.bat assembleDebug" }

& $adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed" }

& (Join-Path $PSScriptRoot "start-public-address-bridge.ps1") -Device $Device
if ($LASTEXITCODE -ne 0) { throw "Public-address helper launch failed" }

& $adb -s $Device shell am start -n com.ivionics.formhr/.MainActivity
if ($LASTEXITCODE -ne 0) { throw "Could not open FormHR" }

Write-Host "FormHR is installed, its public-address helper is running, and the app is open."

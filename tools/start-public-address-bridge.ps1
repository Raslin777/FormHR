param(
    [string]$Device = "",
    [string]$OriginalName = ""
)

$ErrorActionPreference = "Stop"

$sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found: $adb"
}

$adbTarget = if ($Device) { @("-s", $Device) } else { @() }
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb @adbTarget @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

$packagePathLine = Invoke-Adb shell pm path com.ivionics.formhr | Select-Object -First 1
if (-not $packagePathLine -or -not $packagePathLine.StartsWith("package:")) {
    throw "FormHR is not installed on the selected watch"
}
$packagePath = $packagePathLine.Substring("package:".Length).Trim()

$existing = Invoke-Adb shell "ps -A -o PID,ARGS | grep 'app_process /system/bin com.ivionics.formhr.shell.PublicAddressPeripheral' | grep -v grep || true"
foreach ($line in $existing) {
    if ($line -match '^\s*(\d+)\s+') {
        Invoke-Adb shell kill -15 $Matches[1]
    }
}

Start-Sleep -Milliseconds 500
$currentName = (Invoke-Adb shell settings get secure bluetooth_name | Select-Object -First 1).Trim()
if ($OriginalName -and $currentName -eq "Polar OH1 37447F17") {
    Invoke-Adb shell "CLASSPATH=$packagePath app_process /system/bin com.ivionics.formhr.shell.PublicAddressPeripheral set-name $OriginalName" | Out-Host
}

$remoteCommand = "nohup sh -c 'CLASSPATH=$packagePath app_process /system/bin com.ivionics.formhr.shell.PublicAddressPeripheral' >/data/local/tmp/formhr-public-address.log 2>&1 </dev/null &"
Invoke-Adb shell $remoteCommand
Start-Sleep -Seconds 2

Invoke-Adb shell "tail -n 14 /data/local/tmp/formhr-public-address.log" | Out-Host
Write-Host "Public-address bridge started from the installed FormHR APK. Rerun after a watch reboot."

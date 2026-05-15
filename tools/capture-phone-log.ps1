$ErrorActionPreference = "Stop"

$logDir = ".\logs"
if (!(Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = "$logDir\phone-$timestamp.log"
$latestFile = "$logDir\phone-latest.log"

Write-Host "Clearing existing Logcat..."
adb logcat -c

Write-Host "Now reproduce the problem on your phone."
Read-Host "Press Enter after the error happens"

Write-Host "Saving Logcat to $logFile"
adb logcat -d -v time > $logFile
Copy-Item $logFile $latestFile -Force

Write-Host "Saved:"
Write-Host $logFile
Write-Host $latestFile
$ErrorActionPreference = "Stop"

$packageName = "com.cryptotradecoach"
$mainActivity = "com.cryptotradecoach.MainActivity"

$logDir = ".\logs"
if (!(Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = "$logDir\phone-$timestamp.log"
$latestFile = "$logDir\phone-latest.log"

Write-Host "Building and installing debug app..."
.\gradlew.bat :app:installDebug

Write-Host "Clearing Logcat..."
adb logcat -c

Write-Host "Starting app..."
adb shell am force-stop $packageName
adb shell am start -n "$packageName/$mainActivity"

Write-Host "Use the app on your phone and reproduce the issue."
Read-Host "Press Enter after the issue occurs"

Write-Host "Saving Logcat..."
adb logcat -d -v time > $logFile
Copy-Item $logFile $latestFile -Force

Write-Host "Saved:"
Write-Host $logFile
Write-Host $latestFile
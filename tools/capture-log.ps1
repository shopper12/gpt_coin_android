$ErrorActionPreference = "Stop"

if (!(Test-Path ".\logs")) {
    New-Item -ItemType Directory -Path ".\logs" | Out-Null
}

adb logcat -d -v time > .\logs\phone-latest.log

Write-Host "[log] Saved to logs/phone-latest.log"
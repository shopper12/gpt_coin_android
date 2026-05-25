$ErrorActionPreference = "Stop"

Write-Host "[install] Checking connected Android devices..."
$devices = & adb devices | Select-String -Pattern "\tdevice$"
if (-not $devices) {
    throw "No connected Android device detected. Connect the phone, enable USB debugging, accept the RSA prompt, then run: adb devices"
}

Write-Host "[install] Building and installing debug APK..."
& .\gradlew.bat :app:installDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle installDebug failed with exit code $LASTEXITCODE"
}

Write-Host "[install] Installed."

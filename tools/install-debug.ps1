$ErrorActionPreference = "Stop"

Write-Host "[install] Installing debug build..."
.\gradlew.bat :app:installDebug

Write-Host "[install] Installed."
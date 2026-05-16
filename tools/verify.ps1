$ErrorActionPreference = "Stop"

Write-Host "[verify] Building debug APK..."
.\gradlew.bat :app:assembleDebug

Write-Host "[verify] Build passed."
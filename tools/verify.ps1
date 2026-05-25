$ErrorActionPreference = "Stop"

Write-Host "[verify] Building debug APK..."
.\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

Write-Host "[verify] Build passed."

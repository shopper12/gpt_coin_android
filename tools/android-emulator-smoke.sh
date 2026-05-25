#!/usr/bin/env bash
set -euo pipefail

PKG="com.cryptotradecoach"
APK_PATH="${1:-app/build/outputs/apk/release/app-release.apk}"
LOG_DIR="smoke-output"
LOG_FILE="$LOG_DIR/logcat-smoke.log"
SUMMARY_FILE="$LOG_DIR/smoke-summary.txt"
SCREENSHOT_FILE="$LOG_DIR/screenshot-final.png"

mkdir -p "$LOG_DIR"
: > "$SUMMARY_FILE"

say() {
  echo "$1" | tee -a "$SUMMARY_FILE"
}

wait_for_boot() {
  say "Waiting for emulator boot completion"
  adb wait-for-device
  for i in {1..60}; do
    boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [ "$boot_completed" = "1" ]; then
      say "Emulator booted successfully"
      adb shell getprop sys.boot_completed | tee -a "$SUMMARY_FILE" || true
      return 0
    fi
    say "Attempt $i: Emulator still booting..."
    sleep 5
  done
  say "FAIL: Emulator did not report sys.boot_completed=1 within 300 seconds"
  adb shell getprop sys.boot_completed | tee -a "$SUMMARY_FILE" || true
  return 1
}

sdk_version() {
  adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true
}

grant_notification_permission_if_supported() {
  local sdk
  sdk="$(sdk_version)"
  say "Emulator SDK: ${sdk:-unknown}"
  if [ -n "$sdk" ] && [ "$sdk" -ge 33 ] 2>/dev/null; then
    say "Granting POST_NOTIFICATIONS permission"
    adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  else
    say "Skipping POST_NOTIFICATIONS grant: permission exists only on API 33+"
  fi
}

say "Smoke test started"
say "APK: $APK_PATH"

if [ ! -f "$APK_PATH" ]; then
  say "FAIL: APK not found: $APK_PATH"
  exit 1
fi

wait_for_boot
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell input keyevent 82 || true

adb logcat -c

say "Installing APK"
adb install -r "$APK_PATH" | tee -a "$SUMMARY_FILE"
grant_notification_permission_if_supported

say "Launching activity"
adb shell am start -W -n "$PKG/.MainActivity" | tee -a "$SUMMARY_FILE"
sleep 6

# The app uses a top tab row. On the default emulator size this opens Settings,
# then taps Start scanner. If the coordinates miss, the test still catches startup crashes.
say "Trying to open Settings tab and start scanner via UI taps"
adb shell input tap 900 170 || true
sleep 1
adb shell input tap 210 285 || true
sleep 75

adb shell screencap -p "/sdcard/screenshot-final.png" || true
adb pull "/sdcard/screenshot-final.png" "$SCREENSHOT_FILE" >/dev/null 2>&1 || true
adb logcat -d > "$LOG_FILE"

say "Checking process"
if adb shell pidof "$PKG" >/dev/null 2>&1; then
  say "PASS: app process is running"
else
  say "FAIL: app process is not running"
  tail -n 250 "$LOG_FILE" | tee -a "$SUMMARY_FILE"
  exit 1
fi

say "Checking app fatal logs"
if grep -E "Process: $PKG" "$LOG_FILE" >/dev/null; then
  say "FAIL: fatal app crash found in logcat"
  grep -n -A 80 -B 20 -E "FATAL EXCEPTION|Process: $PKG" "$LOG_FILE" | tail -n 250 | tee -a "$SUMMARY_FILE"
  exit 1
fi

say "Checking scanner/upbit logs"
if grep -E "CryptoScanner|Upbit|Scan failed|rate limited|HTTP 429|GitHub token is missing|Rules download failed" "$LOG_FILE" >/dev/null; then
  say "Scanner-related log excerpt:"
  grep -E "CryptoScanner|Upbit|Scan failed|rate limited|HTTP 429|GitHub token is missing|Rules download failed" "$LOG_FILE" | tail -n 120 | tee -a "$SUMMARY_FILE"
else
  say "No scanner-specific log lines found. UI tap may not have started scanner. Startup smoke still passed."
fi

say "Smoke test finished"
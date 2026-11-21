#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w com.michaelactions.webtabs.test/androidx.test.runner.AndroidJUnitRunner > screenshots/instrumentation.txt
cat screenshots/instrumentation.txt
python3 -c 'from pathlib import Path; s=Path("screenshots/instrumentation.txt").read_text(); assert "OK (1 test)" in s and "FAILURES" not in s and "INSTRUMENTATION_FAILED" not in s, s'
adb pull /sdcard/Android/data/com.michaelactions.webtabs/files/smoke.png screenshots/smoke.png

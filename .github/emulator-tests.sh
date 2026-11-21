#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
# 固定 1280x800 / 160dpi 平板几何；AIDA64 黑边回归依赖这个尺寸
adb shell wm size 1280x800
adb shell wm density 160
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e log true com.michaelactions.webtabs.test/androidx.test.runner.AndroidJUnitRunner > screenshots/instrumentation.txt
cat screenshots/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
s = Path("screenshots/instrumentation.txt").read_text()
assert "FAILURES" not in s and "INSTRUMENTATION_FAILED" not in s, s
m = re.search(r"OK \((\d+) tests?\)", s)
assert m, s
assert int(m.group(1)) == 2, s
for name in ("testAllProxyRoutesFullscreenAndKeepOn", "testAidaLetterboxBackgroundAndScope"):
    assert "test=" + name in s, name + " did not run"
assert "INSTRUMENTATION_CODE: -1" in s, s
PY
adb exec-out run-as com.michaelactions.webtabs cat files/smoke.png > screenshots/smoke.png
adb exec-out run-as com.michaelactions.webtabs cat files/aida-letterbox.png > screenshots/aida-letterbox.png
test -s screenshots/smoke.png
test -s screenshots/aida-letterbox.png

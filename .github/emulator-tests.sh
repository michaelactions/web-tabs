#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
# 固定 1280x800 / 160dpi 平板几何；AIDA64 黑边回归依赖这个尺寸
adb shell wm size 1280x800
adb shell wm density 160
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
# -r 输出 raw 状态（每个测试都有 test=），不用 -e log true（logOnly 不执行测试体）
set +e
adb shell am instrument -w -r com.michaelactions.webtabs.test/androidx.test.runner.AndroidJUnitRunner > screenshots/instrumentation.txt
instrument_rc=$?
set -e
cat screenshots/instrumentation.txt
# 断言失败也先把截图和诊断拉出来；失败状态由下面的断言/退出码保留
adb exec-out run-as com.michaelactions.webtabs cat files/smoke.png > screenshots/smoke.png 2>/dev/null || true
adb exec-out run-as com.michaelactions.webtabs cat files/aida-letterbox.png > screenshots/aida-letterbox.png 2>/dev/null || true
adb exec-out run-as com.michaelactions.webtabs cat files/aida-letterbox.txt > screenshots/aida-letterbox.txt 2>/dev/null || true
adb exec-out run-as com.michaelactions.webtabs cat files/plain-page.png > screenshots/plain-page.png 2>/dev/null || true
adb logcat -d -t 2000 > screenshots/logcat.txt 2>/dev/null || true
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
test -s screenshots/smoke.png
test -s screenshots/aida-letterbox.png
test -s screenshots/plain-page.png
if [ "$instrument_rc" -ne 0 ]; then
  echo "am instrument exited $instrument_rc"
  exit "$instrument_rc"
fi

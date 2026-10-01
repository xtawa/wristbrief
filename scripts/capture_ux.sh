#!/usr/bin/env bash
# Dedicated emulator evidence run. No credentials, production accounts or real user content.
set -euo pipefail
module="${1:?module}"
locale="${2:-en-US}"
theme="${3:-light}"
font="${4:-1.0}"
size="${5:-}"
out="$module/build/ux-evidence"
mkdir -p "$out"
if [[ -n "$size" ]]; then
  adb shell wm size "$size"
  adb shell wm density 480
fi
adb shell settings put system font_scale "$font"
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
adb shell input keyevent KEYCODE_WAKEUP
# boot_completed precedes Wear's launcher/first-run surface settling. The regular
# build-based test job naturally waits during compilation; this prebuilt job must too.
sleep 20
adb install -r "$module/build/outputs/apk/debug/$module-debug.apk"
adb install -r "$module/build/outputs/apk/androidTest/debug/$module-debug-androidTest.apk"
if [[ "$module" == mobile ]]; then
  test_class=ink.underflo.wristbrief.mobile.UiEvaluationTest
else
  test_class=ink.underflo.wristbrief.WearUiEvaluationTest
fi
set +e
adb shell am instrument -w -r \
  -e class "$test_class" -e captureUx true \
  -e uxLocale "$locale" -e uxTheme "$theme" -e uxFontScale "$font" \
  ink.underflo.wristbrief.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$out/instrumentation.txt"
instrument_exit=${PIPESTATUS[0]}
set -e
mkdir -p "$out/screenshots"
# Android 11 scoped storage denies adb pull on phone external app directories.
# Export only the dedicated fixture-evidence directory via the debug app's UID.
if adb exec-out run-as ink.underflo.wristbrief tar -C files/ux-evaluation -cf - . > "$out/screenshots.tar"; then
  tar -xf "$out/screenshots.tar" -C "$out/screenshots"
fi
adb logcat -d -t 3000 > "$out/logcat.txt"
printf 'module=%s\nlocale=%s\ntheme=%s\nfont_scale=%s\nfixture_content=true\n' \
  "$module" "$locale" "$theme" "$font" > "$out/configuration.txt"
test "$instrument_exit" = 0
# am instrument may exit zero even when a test fails, so require JUnit's success marker.
grep -Eq '^OK \([0-9]+ tests?\)' "$out/instrumentation.txt"
test -n "$(find "$out/screenshots" -name '*.png' -print -quit)"

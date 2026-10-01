#!/usr/bin/env bash
# Fail at the failing test, not at whichever tile command happens to run last.
set -euo pipefail
font_scale="${1:-1.00}"
if [ "$font_scale" != "1.00" ]; then
  adb shell settings put system font_scale "$font_scale"
  actual_font_scale="$(adb shell settings get system font_scale | tr -d '\r')"
  test "$actual_font_scale" = "$font_scale"
fi
gradle :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.expectedFontScale=$font_scale" --stacktrace
screen_size="$(adb shell wm size | sed -n 's/.* \([0-9][0-9]*\)x\([0-9][0-9]*\)$/\1 \2/p' | tail -n 1)"
test -n "$screen_size"
read -r width height <<< "$screen_size"
tap_x=$((width / 2))
tap_y=$((height / 2))
for tile_service in .tile.LatestUnreadTileService .tile.ContinueListeningTileService; do
  component="ink.underflo.wristbrief/$tile_service"
  add_output="$(adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component "$component" | tr -d '\r')"
  echo "$add_output"
  grep -q 'result=1' <<< "$add_output"
  tile_index="$(sed -n 's/.*Index=\[\([0-9][0-9]*\)\].*/\1/p' <<< "$add_output")"
  test -n "$tile_index"
  show_output="$(adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-tile --ei index "$tile_index" | tr -d '\r')"
  echo "$show_output"
  grep -q 'result=1' <<< "$show_output"
  sleep 1
  adb shell input tap "$tap_x" "$tap_y"
  launched=0
  for attempt in {1..10}; do
    activity="$(adb shell dumpsys activity activities)"
    if grep -q 'mResumedActivity.*ink.underflo.wristbrief/.MainActivity' <<< "$activity"; then
      launched=1
      break
    fi
    sleep 0.5
  done
  test "$launched" = 1
  adb shell input keyevent KEYCODE_BACK
  sleep 0.5
  remove_output="$(adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation remove-tile --ecn component "$component" | tr -d '\r')"
  echo "$remove_output"
  grep -q 'result=1' <<< "$remove_output"
done

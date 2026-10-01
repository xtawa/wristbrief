#!/usr/bin/env bash
# Host-only regression for the historical false-green Wear CI wrapper.
set -euo pipefail
gradle() { return 42; }
adb() { echo "UNEXPECTED_ADB_AFTER_FAILED_GRADLE"; return 0; }
export -f gradle adb
set +e
output="$(bash scripts/verify_wear.sh 1.00 2>&1)"
status=$?
set -e
test "$status" -eq 42
if [[ "$output" == *UNEXPECTED_ADB* ]]; then
  echo "Wear wrapper continued after the test failure" >&2
  exit 1
fi
echo "Wear wrapper propagates Gradle failure and does not execute tile checks"
adb() {
  if [[ "$*" == *"settings get system font_scale"* ]]; then
    echo 1.24
  elif [[ "$*" != *"settings put system font_scale"* ]]; then
    echo "UNEXPECTED_ADB_AFTER_FAILED_GRADLE"
  fi
}
export -f adb
set +e
output="$(bash scripts/verify_wear.sh 1.30 2>&1)"
status=$?
set -e
test "$status" -eq 42
[[ "$output" != *UNEXPECTED_ADB* ]]
echo "Wear's mapped large-font scale is accepted before checking Gradle failure"

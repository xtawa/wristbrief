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

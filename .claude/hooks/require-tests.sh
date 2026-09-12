#!/bin/sh
# require-tests.sh — flag staged source files that have no matching test file.
#
# TYPE:  Stop  (or PreToolUse:Bash matching `git commit` — see README)
# EXITS: mode=warn  -> always 0, prints missing-test notes to stderr
#        mode=block -> 2 when tests are missing (the model is asked to add them)
#
# WHY
# ---
# "Every change ships with a test" is a great norm but easy to forget in an
# autonomous session. This hook inspects the *staged* source files (things you
# deliberately `git add`ed), and for each one under a configured source glob,
# checks whether at least one matching test file exists. Infra / SDK-wrapper /
# generated files are hard to unit-test meaningfully, so they can be excluded.
#
# CONFIG (claude-code-safety-hooks.config.json  ->  "tests")
#   mode         : "warn" (default) or "block"
#   sourceGlobs  : globs that count as testable source
#                  (default src/** and lib/** .ts/.tsx/.js/.jsx)
#   testPatterns : where a test may live, with {dir}/{name}/{ext} placeholders
#                  default:
#                    {dir}/{name}.test.{ext}
#                    {dir}/{name}.spec.{ext}
#                    {dir}/__tests__/{name}.test.{ext}
#   exclude      : globs that are exempt (d.ts, index, config, existing tests, ...)
# ENV OVERRIDES
#   CCSH_TESTS_MODE=block
#   CCSH_DISABLE_REQUIRE_TESTS=1
#
# Only added/copied/modified/renamed files are considered (deletions are exempt:
# removing a module together with its test is a valid change).

set -eu

# CDPATH= scopes an empty CDPATH to this cd only (keeps CDPATH from hijacking it).
# shellcheck disable=SC1007
CCSH_LIB_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CCSH_LIB_DIR
# shellcheck source=_lib.sh
. "$CCSH_LIB_DIR/_lib.sh"

[ "$(ccsh_bool "${CCSH_DISABLE_REQUIRE_TESTS:-0}")" = "1" ] && exit 0

# Move to repo root so staged paths resolve.
ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo "")
[ -n "$ROOT" ] || exit 0
cd "$ROOT" || exit 0

MODE=$(ccsh_config tests.mode warn)

# Staged, added/copied/modified/renamed only.
CHANGED=$(git diff --name-only --cached --diff-filter=ACMR 2>/dev/null || echo "")
[ -n "$CHANGED" ] || exit 0

SOURCE_GLOBS=$(ccsh_config_list tests.sourceGlobs)
[ -n "$SOURCE_GLOBS" ] || SOURCE_GLOBS="src/**/*.ts
src/**/*.tsx
src/**/*.js
src/**/*.jsx
lib/**/*.ts
lib/**/*.js"

EXCLUDE_GLOBS=$(ccsh_config_list tests.exclude)

TEST_PATTERNS=$(ccsh_config_list tests.testPatterns)
[ -n "$TEST_PATTERNS" ] || TEST_PATTERNS='{dir}/{name}.test.{ext}
{dir}/{name}.spec.{ext}
{dir}/__tests__/{name}.test.{ext}'

# glob_match <glob> <path> -> 0 if the path matches the glob (supports ** and *).
# Implemented with a shell `case` after translating the glob into a plain shell
# pattern. In `case`, a single `*` already crosses "/", so a `**/<x>` segment
# (zero-or-more directories) is equivalent to a single `*`: translate `**/` -> `*`
# and any leftover `**` -> `*`. Examples:
#   src/**/*.ts  -> src/*.ts   (matches src/a.ts AND src/x/y/a.ts)
#   **/index.ts  -> *index.ts  (matches index.ts AND src/index.ts)
glob_match() {
  _glob=$1
  _path=$2
  _pat=$(printf '%s' "$_glob" | sed 's#\*\*/#*#g; s#\*\*#*#g')
  # $_pat is intentionally UNquoted so it is matched as a glob, not a literal.
  # shellcheck disable=SC2254
  case "$_path" in
    $_pat) return 0 ;;
    *) return 1 ;;
  esac
}

is_excluded() {
  _p=$1
  [ -n "$EXCLUDE_GLOBS" ] || return 1
  OLDIFS=$IFS; IFS='
'
  for g in $EXCLUDE_GLOBS; do
    [ -n "$g" ] || continue
    if glob_match "$g" "$_p"; then IFS=$OLDIFS; return 0; fi
  done
  IFS=$OLDIFS
  return 1
}

is_source() {
  _p=$1
  OLDIFS=$IFS; IFS='
'
  for g in $SOURCE_GLOBS; do
    [ -n "$g" ] || continue
    if glob_match "$g" "$_p"; then IFS=$OLDIFS; return 0; fi
  done
  IFS=$OLDIFS
  return 1
}

# For a source file, does any configured test pattern resolve to an existing file?
has_test() {
  _src=$1
  _dir=$(dirname "$_src")
  _file=$(basename "$_src")
  _ext=$(printf '%s' "$_file" | sed 's/.*\.//')
  _name=$(printf '%s' "$_file" | sed "s/\.$_ext\$//")

  OLDIFS=$IFS; IFS='
'
  for pat in $TEST_PATTERNS; do
    [ -n "$pat" ] || continue
    _cand=$(printf '%s' "$pat" \
      | sed "s#{dir}#$_dir#g; s#{name}#$_name#g; s#{ext}#$_ext#g")
    if [ -f "$_cand" ]; then IFS=$OLDIFS; return 0; fi
  done
  IFS=$OLDIFS
  return 1
}

MISSING=""
OLDIFS=$IFS; IFS='
'
for f in $CHANGED; do
  [ -n "$f" ] || continue
  is_source "$f" || continue
  is_excluded "$f" && continue
  if ! has_test "$f"; then
    MISSING="$MISSING
  - $f"
  fi
done
IFS=$OLDIFS

[ -n "$MISSING" ] || exit 0

echo "Staged source files with no matching test file:" >&2
printf '%s\n' "$MISSING" >&2
echo "" >&2
echo "Expected a test at one of:" >&2
printf '%s' "$TEST_PATTERNS" | sed 's/^/  /' >&2
echo "" >&2

if [ "$MODE" = "block" ]; then
  echo "Add tests before finishing (tests.mode=block). To relax, set tests.mode=warn" >&2
  echo "in claude-code-safety-hooks.config.json, or exclude the file via tests.exclude." >&2
  exit 2
fi

echo "(tests.mode=warn — not blocking. Consider adding coverage.)" >&2
exit 0

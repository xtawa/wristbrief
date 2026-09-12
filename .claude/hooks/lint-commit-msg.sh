#!/bin/sh
# lint-commit-msg.sh — enforce Conventional Commits on `git commit`.
#
# TYPE:  PreToolUse (matcher: Bash)
# EXITS: 0 = allow, 2 = block (stderr is returned to the model)
#
# WHY
# ---
# Consistent commit subjects keep history greppable and changelogs generatable.
# This hook parses the commit message out of the Bash command Claude is about to
# run (both `-m "..."` and `<<'EOF' ... EOF` heredoc forms) and validates it
# against the Conventional Commits shape:
#
#     type(scope): summary
#
#   type    : one of the configured list (feat, fix, docs, ...)
#   scope   : optional by default; when required, one/more of the configured
#             scopes (comma-separated for multi-package changes)
#   summary : lowercase first letter, no trailing period, within the length cap
#
# Commits with no parseable -m/-heredoc message (e.g. plain `git commit` opening
# an editor, or `-F file`, or `--amend --no-edit`) are left alone — this hook
# only validates messages it can actually see.
#
# CONFIG (claude-code-safety-hooks.config.json  ->  "commit")
#   types            : allowed type list
#   scopes           : allowed scope list ([] = any scope token accepted)
#   requireScope     : true|false   (default false)
#   maxLength        : subject length cap (default 72)
#   lowercaseSubject : enforce lowercase first letter (default true)
#   noTrailingPeriod : forbid a trailing "." (default true)
# ENV OVERRIDES
#   CCSH_COMMIT_MAXLENGTH=50
#   CCSH_DISABLE_LINT_COMMIT_MSG=1

set -eu

# CDPATH= scopes an empty CDPATH to this cd only (keeps CDPATH from hijacking it).
# shellcheck disable=SC1007
CCSH_LIB_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CCSH_LIB_DIR
# shellcheck source=_lib.sh
. "$CCSH_LIB_DIR/_lib.sh"

[ "$(ccsh_bool "${CCSH_DISABLE_LINT_COMMIT_MSG:-0}")" = "1" ] && exit 0

COMMAND=$(ccsh_json tool_input.command "")
[ -n "$COMMAND" ] || exit 0

# Only act on `git commit`.
printf '%s' "$COMMAND" | grep -qE 'git[[:space:]]+commit' || exit 0

# --- Extract the subject line (first line of the message). ---
# python3 handles both -m "msg" / -m 'msg' and heredoc (<<EOF ... ) forms and is
# quote-aware. Fall back to a sed extractor for the -m form if python3 is absent.
if command -v python3 >/dev/null 2>&1; then
  SUBJECT=$(printf '%s' "$COMMAND" | python3 -c '
import sys, re
cmd = sys.stdin.read()
# Heredoc: capture first non-empty content line after <<EOF / <<"EOF" / <<-EOF
h = re.search(r"<<-?\s*[\x27\x22]?([A-Za-z_][A-Za-z0-9_]*)[\x27\x22]?\s*\n(.*?)\n\1", cmd, re.S)
if h:
    body = h.group(2)
    for line in body.splitlines():
        if line.strip():
            print(line.strip()); sys.exit(0)
# -m "..." or -m ...  (first -m wins)
m = re.search(r"-m\s+([\x27\x22])(.*?)\1", cmd, re.S)
if m:
    print(m.group(2).splitlines()[0].strip() if m.group(2).strip() else "")
    sys.exit(0)
m2 = re.search(r"-m[=\s]+([^\s\x27\x22][^\n]*)", cmd)
if m2:
    print(m2.group(1).strip())
' 2>/dev/null || echo "")
else
  SUBJECT=$(printf '%s' "$COMMAND" | tr '\n' ' ' \
    | sed -n "s/.*-m[[:space:]][[:space:]]*[\"']\\([^\"']*\\).*/\\1/p")
fi

# Nothing parseable (editor commit, -F file, --amend --no-edit, etc.) → skip.
[ -n "${SUBJECT:-}" ] || exit 0

# --- Load config ---
TYPES=$(ccsh_config_list commit.types)
[ -n "$TYPES" ] || TYPES="feat
fix
refactor
perf
style
docs
test
chore
revert
build
ci"
SCOPES=$(ccsh_config_list commit.scopes)
REQUIRE_SCOPE=$(ccsh_bool "$(ccsh_config commit.requireScope false)")
MAX_LENGTH=$(ccsh_config commit.maxLength 72)
LOWERCASE=$(ccsh_bool "$(ccsh_config commit.lowercaseSubject true)")
NO_PERIOD=$(ccsh_bool "$(ccsh_config commit.noTrailingPeriod true)")

# Build alternation groups for the regex.
TYPE_ALT=$(printf '%s' "$TYPES" | tr '\n' '|' | sed 's/|$//' | sed 's/||*/|/g')

fail() {
  echo "BLOCKED: commit message is not a valid Conventional Commit." >&2
  echo "  Got:      $SUBJECT" >&2
  echo "  Expected: type(scope): summary" >&2
  echo "  Types:    $(printf '%s' "$TYPES" | tr '\n' ' ')" >&2
  if [ -n "$SCOPES" ]; then
    echo "  Scopes:   $(printf '%s' "$SCOPES" | tr '\n' ' ')$([ "$REQUIRE_SCOPE" = 1 ] && echo ' (required)' || echo ' (optional)')" >&2
  elif [ "$REQUIRE_SCOPE" = 1 ]; then
    echo "  Scope:    required (any token)" >&2
  fi
  RULES=""
  [ "$LOWERCASE" = 1 ] && RULES="lowercase first letter"
  [ "$NO_PERIOD" = 1 ] && RULES="${RULES:+$RULES, }no trailing period"
  RULES="${RULES:+$RULES, }≤ $MAX_LENGTH chars"
  echo "  Rules:    $RULES" >&2
  echo "  Example:  feat(auth): add refresh-token rotation" >&2
  exit 2
}

# --- 1. Structural check: type(scope)?: summary ---
# Scope group: if a specific scope list is configured, restrict to it (allowing
# comma-separated multi-scope); otherwise accept any non-paren token.
if [ -n "$SCOPES" ]; then
  SCOPE_ALT=$(printf '%s' "$SCOPES" | tr '\n' '|' | sed 's/|$//' | sed 's/||*/|/g')
  SCOPE_TOKEN="($SCOPE_ALT)(,($SCOPE_ALT))*"
else
  SCOPE_TOKEN='[a-z0-9][a-z0-9._/-]*(,[a-z0-9][a-z0-9._/-]*)*'
fi

if [ "$REQUIRE_SCOPE" = 1 ]; then
  SCOPE_PART="\\($SCOPE_TOKEN\\)"
else
  SCOPE_PART="(\\($SCOPE_TOKEN\\))?"
fi

# Optional breaking-change marker "!" before the colon (Conventional Commits).
STRUCT="^($TYPE_ALT)${SCOPE_PART}!?: .+"
printf '%s' "$SUBJECT" | grep -qE "$STRUCT" || fail

# --- 2. Extract the summary (text after the first ": ") ---
SUMMARY=$(printf '%s' "$SUBJECT" | sed 's/^[^:]*: //')
[ -n "$SUMMARY" ] || fail

# --- 3. Lowercase first letter ---
if [ "$LOWERCASE" = 1 ]; then
  case "$SUMMARY" in
    [A-Z]*) fail ;;
  esac
fi

# --- 4. No trailing period ---
if [ "$NO_PERIOD" = 1 ]; then
  case "$SUMMARY" in
    *.) fail ;;
  esac
fi

# --- 5. Length cap (measured on the whole subject line) ---
LEN=$(printf '%s' "$SUBJECT" | wc -c | tr -d ' ')
if [ "$LEN" -gt "$MAX_LENGTH" ]; then
  fail
fi

exit 0

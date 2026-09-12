#!/bin/sh
# block-force-push.sh — deny `git push --force` / `--force-with-lease`.
#
# TYPE:  PreToolUse (matcher: Bash)
# EXITS: 0 = allow, 2 = block (stderr is returned to the model)
#
# WHY
# ---
# A force-push can silently overwrite teammates' commits on a shared branch and
# is one of the easiest irreversible mistakes to make in an autonomous session.
# This hook blocks it. Deleting a remote branch (`git push origin --delete X`
# or `git push origin :X`) is NOT a history rewrite and is allowed through.
#
# CONFIG (claude-code-safety-hooks.config.json)
#   forcePush.enabled : true|false   (default true)
# ENV OVERRIDES
#   CCSH_FORCEPUSH_ENABLED=false      to disable
#   CCSH_ALLOW_FORCE_PUSH=1           one-off escape hatch (lets a single push through)
#
# Fails OPEN on parse errors (a payload we can't read is never blocked).

set -eu

# CDPATH= scopes an empty CDPATH to this cd only (keeps CDPATH from hijacking it).
# shellcheck disable=SC1007
CCSH_LIB_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CCSH_LIB_DIR
# shellcheck source=_lib.sh
. "$CCSH_LIB_DIR/_lib.sh"

# Master switch (config) + one-off escape hatch (env).
[ "$(ccsh_bool "$(ccsh_config forcePush.enabled true)")" = "1" ] || exit 0
[ "$(ccsh_bool "${CCSH_ALLOW_FORCE_PUSH:-0}")" = "1" ] && exit 0

COMMAND=$(ccsh_json tool_input.command "")
[ -n "$COMMAND" ] || exit 0

# Only care about `git push`.
printf '%s' "$COMMAND" | grep -qE 'git[[:space:]]+push' || exit 0

# Allow branch deletion — it doesn't rewrite history.
#   git push origin --delete <branch>   |   git push origin :<branch>
if printf '%s' "$COMMAND" | grep -qE 'git[[:space:]]+push[[:space:]].*(--delete|[[:space:]]:[A-Za-z0-9._/-]+)'; then
  exit 0
fi

# Block force in any form: -f, --force, --force-with-lease, --force-if-includes.
if printf '%s' "$COMMAND" | grep -qE 'git[[:space:]]+push[[:space:]].*(-f\b|--force(-with-lease|-if-includes)?\b)'; then
  echo "BLOCKED: force-pushing (git push --force / --force-with-lease) is not allowed." >&2
  echo "  A force-push can overwrite commits others have already pushed." >&2
  echo "  If you truly need to rewrite remote history, do it yourself outside the agent," >&2
  echo "  or set CCSH_ALLOW_FORCE_PUSH=1 for this one command." >&2
  echo "  (Deleting a remote branch with --delete or :branch is allowed and not blocked.)" >&2
  exit 2
fi

exit 0

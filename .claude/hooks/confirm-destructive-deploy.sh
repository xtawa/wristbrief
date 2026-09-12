#!/bin/sh
# confirm-destructive-deploy.sh — gate a deploy/release command behind an
# explicit confirmation env var.
#
# TYPE:  PreToolUse (matcher: Bash)
# EXITS: 0 = allow, 2 = block (stderr is returned to the model)
#
# WHY
# ---
# A deploy or release command is money- and user-facing: run it by accident in
# an autonomous session and you ship to production. This hook teaches the
# "confirm before you deploy" pattern generically: it blocks any command
# matching a configurable pattern UNLESS an explicit confirmation env var is set
# (either exported into the hook's environment, or typed inline in the command).
# That forces a deliberate, auditable step before anything ships — the same
# staging-before-prod discipline, with none of the vendor specifics.
#
# CONFIG (claude-code-safety-hooks.config.json  ->  "deploy")
#   commandPattern : extended-regex that identifies a deploy command
#                    (default "(deploy|release)\\b")
#   confirmEnv     : name of the env var that unlocks it
#                    (default "CCSH_ALLOW_DEPLOY")
#   message        : custom block message (optional)
# ENV OVERRIDES
#   CCSH_DEPLOY_COMMANDPATTERN='gcloud .*deploy'
#   CCSH_DEPLOY_CONFIRMENV='I_KNOW_WHAT_I_AM_DOING'
#   CCSH_DISABLE_CONFIRM_DEPLOY=1
#
# To ACTUALLY deploy, set the confirmation var, e.g.:
#   CCSH_ALLOW_DEPLOY=1 <your deploy command>
# or export it for the session before invoking the deploy.
#
# Fails OPEN on parse errors.

set -eu

# CDPATH= scopes an empty CDPATH to this cd only (keeps CDPATH from hijacking it).
# shellcheck disable=SC1007
CCSH_LIB_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CCSH_LIB_DIR
# shellcheck source=_lib.sh
. "$CCSH_LIB_DIR/_lib.sh"

[ "$(ccsh_bool "${CCSH_DISABLE_CONFIRM_DEPLOY:-0}")" = "1" ] && exit 0

COMMAND=$(ccsh_json tool_input.command "")
[ -n "$COMMAND" ] || exit 0

PATTERN=$(ccsh_config deploy.commandPattern '(deploy|release)\b')
CONFIRM_ENV=$(ccsh_config deploy.confirmEnv 'CCSH_ALLOW_DEPLOY')
MESSAGE=$(ccsh_config deploy.message '')

# Does the command look like a deploy?
printf '%s' "$COMMAND" | grep -qE "$PATTERN" || exit 0

# Confirmation can arrive two ways:
#   1. Exported into the hook's environment (CI wrapper / operator `export`).
#   2. Typed inline as a leading assignment (CONFIRM_ENV=1 <cmd>) — in which
#      case it is NOT yet in our environment, so we grep the command text.
eval "_confirm_val=\${$CONFIRM_ENV:-}"
if [ "$(ccsh_bool "${_confirm_val:-0}")" = "1" ]; then
  echo "confirm-destructive-deploy: $CONFIRM_ENV set — deploy allowed." >&2
  exit 0
fi
if printf '%s' "$COMMAND" | grep -qE "(^|[[:space:]])$CONFIRM_ENV=([1-9]|true|yes|on)([[:space:]]|\$)"; then
  echo "confirm-destructive-deploy: $CONFIRM_ENV set inline — deploy allowed." >&2
  exit 0
fi

# --- Blocked ---
if [ -n "$MESSAGE" ]; then
  echo "BLOCKED: $MESSAGE" >&2
else
  echo "BLOCKED: this looks like a deploy/release command and shipping is gated." >&2
fi
echo "" >&2
echo "  Command: $COMMAND" >&2
echo "" >&2
echo "  Deploying is deliberate: verify a non-production environment first, then" >&2
echo "  re-run WITH the confirmation flag so it can't happen by accident:" >&2
echo "" >&2
echo "      $CONFIRM_ENV=1 <your deploy command>" >&2
echo "" >&2
echo "  (Or export $CONFIRM_ENV=1 for the session. To change what counts as a" >&2
echo "  deploy, edit deploy.commandPattern in claude-code-safety-hooks.config.json.)" >&2
exit 2

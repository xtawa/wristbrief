#!/bin/sh
# guard-dev-ports.sh — free dev-server ports before a dev command runs.
#
# TYPE:  PreToolUse (matcher: Bash)
# EXITS: always 0 (advisory / housekeeping — never blocks the command)
#
# WHY
# ---
# Restarting a dev server when the previous one is still bound to its port
# fails with EADDRINUSE and derails the session. This hook watches for
# dev-server commands (configurable list) and, when it sees one, kills whatever
# is already listening on the configured ports so startup is clean.
#
# CONFIG (claude-code-safety-hooks.config.json)
#   devPorts.ports    : array of ports to free      (default 3000,3001,5173,8080,8081)
#   devPorts.triggers : substrings that mark a dev command
#                       (default: "npm run dev","pnpm dev","next dev","vite","expo start", ...)
# ENV OVERRIDES
#   CCSH_DEVPORTS_PORTS="3000,4000"     (comma/space separated)
#   CCSH_DEVPORTS_TRIGGERS="npm start"  (comma/space separated)
#   CCSH_DISABLE_GUARD_DEV_PORTS=1      to skip entirely
#
# NOTE: this hook only ever *frees* ports it is configured to manage. It never
# blocks the command, so if the kill is unnecessary nothing observable changes.

set -eu

# Resolve the directory holding this script so we can source the shared lib
# CDPATH= scopes an empty CDPATH to this cd only (keeps CDPATH from hijacking it).
# shellcheck disable=SC1007
CCSH_LIB_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CCSH_LIB_DIR
# shellcheck source=_lib.sh
. "$CCSH_LIB_DIR/_lib.sh"

[ "$(ccsh_bool "${CCSH_DISABLE_GUARD_DEV_PORTS:-0}")" = "1" ] && exit 0

COMMAND=$(ccsh_json tool_input.command "")
[ -n "$COMMAND" ] || exit 0

# --- Does this command look like a dev-server start? ---
TRIGGERS=$(ccsh_config_list devPorts.triggers)
if [ -z "$TRIGGERS" ]; then
  TRIGGERS="npm run dev
pnpm dev
pnpm run dev
yarn dev
next dev
vite
expo start"
fi

is_dev=0
# Read triggers line by line; substring match against the command.
OLDIFS=$IFS
IFS='
'
for t in $TRIGGERS; do
  [ -n "$t" ] || continue
  case "$COMMAND" in
    *"$t"*) is_dev=1; break ;;
  esac
done
IFS=$OLDIFS

[ "$is_dev" = "1" ] || exit 0

# --- Collect configured ports ---
PORTS=$(ccsh_config_list devPorts.ports)
if [ -z "$PORTS" ]; then
  PORTS="3000
3001
5173
8080
8081"
fi

# Build a comma list for lsof (lsof -ti:3000,3001,...)
PORT_CSV=$(printf '%s' "$PORTS" | tr '\n' ',' | sed 's/,$//' | sed 's/,,*/,/g')
[ -n "$PORT_CSV" ] || exit 0

if command -v lsof >/dev/null 2>&1; then
  PIDS=$(lsof -ti:"$PORT_CSV" 2>/dev/null || true)
  if [ -n "$PIDS" ]; then
    # shellcheck disable=SC2086
    kill -9 $PIDS 2>/dev/null || true
    echo "guard-dev-ports: freed ports $PORT_CSV before starting dev server" >&2
  fi
else
  # No lsof (e.g. minimal container). Try fuser as a fallback, otherwise no-op.
  if command -v fuser >/dev/null 2>&1; then
    OLDIFS=$IFS
    IFS='
'
    for p in $PORTS; do
      [ -n "$p" ] || continue
      fuser -k "${p}/tcp" >/dev/null 2>&1 || true
    done
    IFS=$OLDIFS
    echo "guard-dev-ports: freed ports $PORT_CSV via fuser" >&2
  fi
fi

exit 0

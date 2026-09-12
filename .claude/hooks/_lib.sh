#!/bin/sh
# _lib.sh — shared helpers for claude-code-safety-hooks
#
# POSIX sh. Sourced by every hook. Provides:
#   - ccsh_read_stdin           : slurp the hook's JSON payload from stdin (once)
#   - ccsh_json <path> [default]: read a value out of that payload
#   - ccsh_config <key> [default]: read a value out of the config file
#   - ccsh_config_list <key>    : read an array from the config file (newline-joined)
#   - ccsh_bool <value>         : normalise truthy strings to "1"/"0"
#
# Design notes
# ------------
# * Claude Code invokes a hook with a JSON object on stdin, e.g.
#     {"tool_name":"Bash","tool_input":{"command":"git push --force"}}
#   The exact envelope is documented at
#   https://docs.claude.com/en/docs/claude-code/hooks
# * Hooks communicate their verdict via EXIT CODE:
#     exit 0  -> allow (stdout shown to the user, ignored by the model)
#     exit 2  -> block; stderr is fed back to the model so it can correct course
#     other   -> non-blocking error (stderr shown to the user)
#   Everything here fails OPEN (exit 0) on parse errors so a malformed payload
#   never wedges the user's session.
# * JSON parsing prefers python3 (present on virtually every dev machine and on
#   macOS by default). If python3 is missing we fall back to a best-effort sed
#   extractor that handles the flat string fields these hooks care about.

# ---------------------------------------------------------------------------
# Config file discovery
# ---------------------------------------------------------------------------
# Search order (first hit wins):
#   1. $CCSH_CONFIG                                  (explicit override)
#   2. ./claude-code-safety-hooks.config.json        (repo root / cwd)
#   3. ./.claude/claude-code-safety-hooks.config.json
#   4. <dir containing this lib>/../claude-code-safety-hooks.config.json
#      (the copy shipped alongside the hooks)
ccsh_find_config() {
  if [ -n "${CCSH_CONFIG:-}" ] && [ -f "${CCSH_CONFIG}" ]; then
    printf '%s\n' "${CCSH_CONFIG}"
    return 0
  fi
  # git root, if we are inside a repo
  _ccsh_root=$(git rev-parse --show-toplevel 2>/dev/null || echo "")
  for _c in \
    "./claude-code-safety-hooks.config.json" \
    "./.claude/claude-code-safety-hooks.config.json" \
    "${_ccsh_root:+$_ccsh_root/claude-code-safety-hooks.config.json}" \
    "${_ccsh_root:+$_ccsh_root/.claude/claude-code-safety-hooks.config.json}" \
    "${CCSH_LIB_DIR:-.}/../claude-code-safety-hooks.config.json"
  do
    [ -n "$_c" ] || continue
    if [ -f "$_c" ]; then
      printf '%s\n' "$_c"
      return 0
    fi
  done
  return 1
}

# ccsh_read_stdin — read the hook payload from stdin exactly once and cache it
# in $CCSH_STDIN. Safe to call multiple times.
ccsh_read_stdin() {
  if [ -z "${CCSH_STDIN_READ:-}" ]; then
    CCSH_STDIN=$(cat 2>/dev/null || echo "")
    CCSH_STDIN_READ=1
    export CCSH_STDIN CCSH_STDIN_READ
  fi
}

# ccsh_json <dotted.path> [default]
# Reads a value from the cached stdin JSON. Path is dot-delimited, e.g.
#   ccsh_json tool_input.command
#   ccsh_json tool_name
ccsh_json() {
  _path="$1"
  _default="${2:-}"
  ccsh_read_stdin
  if [ -z "${CCSH_STDIN:-}" ]; then
    printf '%s' "$_default"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$CCSH_STDIN" | CCSH_PATH="$_path" CCSH_DEFAULT="$_default" python3 -c '
import sys, json, os
path = os.environ.get("CCSH_PATH", "")
default = os.environ.get("CCSH_DEFAULT", "")
try:
    data = json.load(sys.stdin)
except Exception:
    print(default, end="")
    sys.exit(0)
cur = data
for part in path.split("."):
    if isinstance(cur, dict) and part in cur:
        cur = cur[part]
    else:
        cur = default
        break
if cur is None:
    cur = default
print(cur if isinstance(cur, str) else json.dumps(cur), end="")
' 2>/dev/null || printf '%s' "$_default"
  else
    # Fallback: grab the last path segment as a flat "key":"value" pair.
    # Handles tool_name / tool_input.command / tool_input.file_path etc.
    _key=$(printf '%s' "$_path" | sed 's/.*\.//')
    _val=$(printf '%s' "$CCSH_STDIN" \
      | tr -d '\n' \
      | sed -n "s/.*\"$_key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p")
    if [ -n "$_val" ]; then
      printf '%s' "$_val"
    else
      printf '%s' "$_default"
    fi
  fi
}

# ccsh_config <dotted.key> [default]
# Reads a scalar value from the config file. Env override always wins: the
# uppercased, dot->underscore form prefixed with CCSH_ (e.g. commit.maxLength
# -> CCSH_COMMIT_MAXLENGTH) takes precedence when set.
ccsh_config() {
  _key="$1"
  _default="${2:-}"

  # Env override
  _env_name="CCSH_$(printf '%s' "$_key" | tr '.a-z' '_A-Z' )"
  eval "_env_val=\${$_env_name:-}"
  if [ -n "${_env_val:-}" ]; then
    printf '%s' "$_env_val"
    return 0
  fi

  _cfg=$(ccsh_find_config 2>/dev/null || echo "")
  if [ -z "$_cfg" ] || ! command -v python3 >/dev/null 2>&1; then
    printf '%s' "$_default"
    return 0
  fi
  CCSH_KEY="$_key" CCSH_DEFAULT="$_default" python3 -c '
import sys, json, os
key = os.environ.get("CCSH_KEY", "")
default = os.environ.get("CCSH_DEFAULT", "")
try:
    with open(sys.argv[1]) as f:
        data = json.load(f)
except Exception:
    print(default, end=""); sys.exit(0)
cur = data
for part in key.split("."):
    if isinstance(cur, dict) and part in cur:
        cur = cur[part]
    else:
        cur = None
        break
if cur is None:
    print(default, end="")
elif isinstance(cur, bool):
    print("true" if cur else "false", end="")
elif isinstance(cur, (list, dict)):
    print(json.dumps(cur), end="")
else:
    print(cur, end="")
' "$_cfg" 2>/dev/null || printf '%s' "$_default"
}

# ccsh_config_list <dotted.key>
# Reads a JSON array from the config and prints one element per line.
# Env override: CCSH_<KEY> may be a comma- OR space-separated string.
ccsh_config_list() {
  _key="$1"

  _env_name="CCSH_$(printf '%s' "$_key" | tr '.a-z' '_A-Z')"
  eval "_env_val=\${$_env_name:-}"
  if [ -n "${_env_val:-}" ]; then
    printf '%s' "$_env_val" | tr ', ' '\n\n' | sed '/^$/d'
    return 0
  fi

  _cfg=$(ccsh_find_config 2>/dev/null || echo "")
  if [ -z "$_cfg" ] || ! command -v python3 >/dev/null 2>&1; then
    return 0
  fi
  CCSH_KEY="$_key" python3 -c '
import sys, json, os
key = os.environ.get("CCSH_KEY", "")
try:
    with open(sys.argv[1]) as f:
        data = json.load(f)
except Exception:
    sys.exit(0)
cur = data
for part in key.split("."):
    if isinstance(cur, dict) and part in cur:
        cur = cur[part]
    else:
        cur = None
        break
if isinstance(cur, list):
    for item in cur:
        print(item)
' "$_cfg" 2>/dev/null || true
}

# ccsh_bool <value> — echo "1" for truthy strings, "0" otherwise.
ccsh_bool() {
  case "$(printf '%s' "${1:-}" | tr 'A-Z' 'a-z')" in
    1|true|yes|on) printf '1' ;;
    *) printf '0' ;;
  esac
}

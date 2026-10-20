#!/bin/bash
# prune-pointers helper + statusline's opportunistic prune.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init prune

mkdir -p "$STATE_DIR/_by_workspace"

# Pointer to existing session (legacy single-file form)
echo "live" > "$STATE_DIR/_by_workspace/aaa"
printf 'row\n' > "$STATE_DIR/live"

# Pointer to non-existent session
echo "ghost" > "$STATE_DIR/_by_workspace/bbb"

# Malformed pointers
echo "../escape" > "$STATE_DIR/_by_workspace/ccc"
: > "$STATE_DIR/_by_workspace/ddd"

bash "$HELPER" prune-pointers
assert_file_exists  "$STATE_DIR/_by_workspace/aaa"
assert_file_missing "$STATE_DIR/_by_workspace/bbb"
# The helper's prune-pointers now also catches malformed (ccc/ddd)
assert_file_missing "$STATE_DIR/_by_workspace/ccc"
assert_file_missing "$STATE_DIR/_by_workspace/ddd"

# --- Statusline's opportunistic prune (force on for determinism)
echo "ghost-also" > "$STATE_DIR/_by_workspace/eee"
CLAUDE_STATUSLINE_FORCE_PRUNE=1 render_status > /dev/null
assert_file_missing "$STATE_DIR/_by_workspace/eee"

# --- New layout: stale marker (no state file, mtime > grace) gets pruned
mkdir -p "$STATE_DIR/_by_workspace/wsX"
touch "$STATE_DIR/_by_workspace/wsX/stale-session"
touch -d "1 hour ago" "$STATE_DIR/_by_workspace/wsX/stale-session"
CLAUDE_STATUSLINE_FORCE_PRUNE=1 CLAUDE_STATUSLINE_MARKER_GRACE=300 render_status > /dev/null
[ ! -f "$STATE_DIR/_by_workspace/wsX/stale-session" ] || _fail "stale marker not pruned"

# --- New layout: fresh marker (no state file, mtime < grace) is kept
mkdir -p "$STATE_DIR/_by_workspace/wsY"
touch "$STATE_DIR/_by_workspace/wsY/fresh-session"
CLAUDE_STATUSLINE_FORCE_PRUNE=1 CLAUDE_STATUSLINE_MARKER_GRACE=300 render_status > /dev/null
[ -f "$STATE_DIR/_by_workspace/wsY/fresh-session" ] || _fail "fresh marker incorrectly pruned"

echo "pointer prune ok"

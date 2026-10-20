#!/bin/bash
# post-push-ci.sh negative cases: non-push commands, no transcript,
# gh pr view returns nothing, malformed JSON tolerated.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init neg

# --- ls is not a push → no state side effects
hook_input_bash "ls /tmp" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# --- gh pr edit is not a push
hook_input_bash "gh pr edit 123 --title foo" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# --- gh pr create but `gh pr view` returns empty → silently no-op
# (no fixture means our mock returns empty stdout)
hook_input_bash "gh pr create -H ghost" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"
assert_file_missing "$CI_DIR/push-pending-$SK"

# --- No transcript field → silently exits
echo '{"tool_name":"Bash","tool_input":{"command":"gh pr create -H foo"},"cwd":"'"$REPO"'","tool_response":{"success":true}}' \
  | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# --- mcp create with no head → silently exits
echo '{"tool_name":"mcp__github__create_pull_request","tool_input":{},"cwd":"'"$REPO"'","transcript_path":"'"$TX"'","tool_response":{"success":true}}' \
  | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

echo "all negative checks ok"

#!/bin/bash
# Malformed shell commands (unmatched quote) must not crash the hook.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init bad

# Unmatched single quote
set +e
echo '{"tool_name":"Bash","tool_input":{"command":"echo '\''unclosed"},"cwd":"'"$REPO"'","transcript_path":"'"$TX"'","tool_response":{"success":true}}' \
  | bash "$HOOK"
rc=$?
set -e
assert_equal "$rc" "0" "hook exits 0 on unmatched quote"
assert_file_missing "$STATE_DIR/$SK"

# Unmatched double quote
set +e
echo '{"tool_name":"Bash","tool_input":{"command":"gh pr create -H \"unclosed"},"cwd":"'"$REPO"'","transcript_path":"'"$TX"'","tool_response":{"success":true}}' \
  | bash "$HOOK"
rc=$?
set -e
assert_equal "$rc" "0" "hook exits 0 on unmatched double quote"
assert_file_missing "$STATE_DIR/$SK"

echo "malformed shlex ok"

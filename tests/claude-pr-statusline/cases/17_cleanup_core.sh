#!/bin/bash
# cleanup-pr-state-core: walks every pr-state/* file, drops merged/closed,
# deletes empty files, prunes pointers, preserves rows on transport failure.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init

# Two session files with a mix of states.
seed_state_row_into "$STATE_DIR/sess-a" "$REPO" keep-1     https://example.com/pr/1 develop 1
seed_state_row_into "$STATE_DIR/sess-a" "$REPO" merged-1   https://example.com/pr/2 develop 2
seed_state_row_into "$STATE_DIR/sess-a" "$REPO" closed-1   https://example.com/pr/3 develop 3
seed_state_row_into "$STATE_DIR/sess-b" "$REPO" gone-1     https://example.com/pr/9 develop 9

# Pointer to sess-a (kept), pointer to sess-b (becomes dangling).
ws_a=$(md5 "/some/workspace-a")
ws_b=$(md5 "/some/workspace-b")
echo "sess-a" > "$STATE_DIR/_by_workspace/$ws_a"
echo "sess-b" > "$STATE_DIR/_by_workspace/$ws_b"

gh_fixture_state https://example.com/pr/1 OPEN
gh_fixture_state https://example.com/pr/2 MERGED
gh_fixture_state https://example.com/pr/3 CLOSED
# pr/9 has no fixture → exits 0 with empty stdout → unreachable

out=$(bash "$CLEANUP")

assert_file_exists "$STATE_DIR/sess-a"
assert_equal "$(wc -l < "$STATE_DIR/sess-a")" "1" "sess-a has 1 row after cleanup"
assert_contains     "$(cat "$STATE_DIR/sess-a")" "keep-1"   "OPEN row kept"
assert_not_contains "$(cat "$STATE_DIR/sess-a")" "merged-1" "MERGED dropped"
assert_not_contains "$(cat "$STATE_DIR/sess-a")" "closed-1" "CLOSED dropped"
assert_file_missing "$STATE_DIR/sess-b"

assert_file_exists  "$STATE_DIR/_by_workspace/$ws_a"
assert_file_missing "$STATE_DIR/_by_workspace/$ws_b"

assert_contains "$out" "scanned=2"        "scanned count"
assert_contains "$out" "merged=1"         "merged count"
assert_contains "$out" "closed=1"         "closed count"
assert_contains "$out" "unreachable=1"    "unreachable count"
assert_contains "$out" "files_deleted=1"  "files deleted count"

# --- Transport failure preserves rows across the whole sweep
seed_state_row_into "$STATE_DIR/sess-c" "$REPO" keep-net https://example.com/pr/100 develop 100
gh_fixture_reset
gh_fixture_raw "pr view https://example.com/pr/100 --json state" "" 4
bash "$CLEANUP" > /dev/null
assert_file_exists "$STATE_DIR/sess-c"
assert_contains "$(cat "$STATE_DIR/sess-c")" "keep-net" "preserved on transport failure"

echo "cleanup-pr-state-core ok"

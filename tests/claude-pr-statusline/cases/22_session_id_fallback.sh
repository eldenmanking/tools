#!/bin/bash
# session_id fallback when transcript_path is absent.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init  # no session needed — using session_id directly

gh_fixture_pr feat-sid OPEN develop 55

session_id="abc-123-fake-session"
sk=$(md5 "$session_id")

# Hook: no transcript_path, only session_id
echo '{"tool_name":"Bash","tool_input":{"command":"gh pr create -H feat-sid"},"cwd":"'"$REPO"'","session_id":"'"$session_id"'","tool_response":{"success":true}}' \
  | bash "$HOOK"
assert_file_exists "$STATE_DIR/$sk"
assert_contains "$(cat "$STATE_DIR/$sk")" "feat-sid" "row written under session_id key"

# Statusline: same session_id, no transcript_path → renders the same row
(cd "$REPO" && git checkout -q -b feat-sid)
out=$(echo '{"workspace":{"current_dir":"'"$REPO"'"},"context_window":{"used_percentage":42},"session_id":"'"$session_id"'"}' \
  | bash "$SL" | strip_ansi)
assert_contains "$out" "feat-sid" "statusline renders under session_id"

echo "session_id fallback ok"

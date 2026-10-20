#!/bin/bash
# End-to-end lifecycle: gh pr create → hook → statusline → Stop nudge →
# refresh-core clears flag → next Stop is silent.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init life

gh_fixture_pr     feat-life                  OPEN develop 100
gh_fixture_pr_url https://example.com/pr/100 OPEN develop feat-life 100

(cd "$REPO" && git checkout -q -b feat-life)

# --- Step 1: hook fires
hook_input_bash "gh pr create -H feat-life" "$REPO" "$TX" | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
assert_file_exists "$CI_DIR/push-pending-$SK"

# --- Step 2: statusline renders the row
out=$(render_status)
assert_contains "$out" "feat-life"                  "row rendered"
assert_contains "$out" "https://example.com/pr/100" "URL rendered"
assert_contains "$out" "▶"                          "current row marked"

# --- Step 3: stop hook nudges (soft = exit 0)
set +e
nudge_out=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
nudge_rc=$?
set -e
assert_equal    "$nudge_rc"  "0"                          "stop hook is non-blocking by default (soft)"
assert_contains "$nudge_out" "https://example.com/pr/100" "nudge includes PR URL"
assert_contains "$nudge_out" "/babysit-ci"                "nudge mentions /babysit-ci"

# Strict mode reverts to exit 2
set +e
nudge_out=$(printf '{"transcript_path":"%s"}\n' "$TX" | CLAUDE_PR_STATUSLINE_STRICT=1 bash "$STOP" 2>&1)
nudge_rc=$?
set -e
assert_equal    "$nudge_rc"  "2"            "strict mode blocks with exit 2"
assert_contains "$nudge_out" "MUST"         "strict nudge demands action"
assert_contains "$nudge_out" "clear-flag"   "strict nudge includes clear-flag step"

# --- Step 4: refresh-core clears the flag
printf '' | bash "$REFRESH" "$STATE_DIR/$SK" > /dev/null
assert_file_missing "$CI_DIR/push-pending-$SK"

# --- Step 5: next stop is silent
set +e
out2=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
rc2=$?
set -e
assert_equal "$rc2"  "0" "stop hook silent after flag clear"
assert_equal "$out2" ""  "no nudge output"

echo "lifecycle ok"

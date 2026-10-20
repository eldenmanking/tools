#!/bin/bash
# MCP propagation race: the GitHub MCP server's create_pull_request often
# returns ONLY {id, url} in tool_response — not the full PR data. Without
# this fix, the fast-path's required-fields gate fails, and the fallback
# `gh pr view <head>` hits GitHub's eventual-consistency window: returns
# 404 → empty → no row, no flag, no Stop reminder.
#
# Fix: the fast-path also pulls `head` and `base` from tool_input (which
# the MCP client passed in), parses `number` from the URL, and defaults
# state to OPEN/DRAFT based on tool_input.draft. No gh call needed.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init mcpmin

# NO gh fixture. If the hook re-queries via gh, it gets empty → no row
# (the race condition we are trying to fix).
gh_fixture_reset

mcp_input() {
  # Args: head, base, draft, response_json
  jq -nc \
    --arg head "$1" \
    --arg base "$2" \
    --argjson draft "$3" \
    --arg cwd "$REPO" \
    --arg tx "$TX" \
    --argjson resp "$4" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head, base:$base, draft:$draft},
      cwd:$cwd,
      transcript_path:$tx,
      tool_response:($resp + {success:true})}'
}

# --- Minimal MCP response: {id, url} only. Fast-path must still write.
mcp_input feat-minimal develop false \
  '{"id":"12889199","url":"https://example.com/pr/123"}' | bash "$HOOK"

assert_file_exists "$STATE_DIR/$SK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-minimal\t'           "head from tool_input"
assert_contains "$row" $'\tdevelop\t'                "base from tool_input"
assert_contains "$row" "https://example.com/pr/123"  "url from tool_response"
assert_contains "$row" $'\t123'                      "number parsed from URL"
assert_file_exists "$CI_DIR/push-pending-$SK"

# Verify the hook did NOT invoke `gh pr view` (would prove the fast-path took the partial-data branch)
if [ -s "$GH_MOCK_LOG" ] && grep -q "pr view" "$GH_MOCK_LOG"; then
  _fail "hook called gh pr view despite tool_input having head/base"
fi

# --- Draft PR: state should be DRAFT, not OPEN.
rm -f "$STATE_DIR/$SK"
: > "$GH_MOCK_LOG"
mcp_input feat-draft develop true \
  '{"id":"100","url":"https://example.com/pr/200"}' | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
# Draft state passes pr_is_alive (DRAFT is alive), so the row should be written.
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-draft\t' "draft row written"

# --- Truly empty tool_response (no url even): must fall back, gh fixture provided.
rm -f "$STATE_DIR/$SK"
gh_fixture_pr feat-fallback OPEN develop 99
mcp_input feat-fallback develop false '{}' | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-fallback\t' "fell back to gh when no URL"

echo "mcp minimal response ok"

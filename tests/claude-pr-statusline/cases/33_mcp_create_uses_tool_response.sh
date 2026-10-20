#!/bin/bash
# Race fix: for mcp__github__create_pull_request, the hook MUST pull PR
# data straight from tool_response (which is guaranteed to be present —
# the MCP call just returned it). Re-querying via `gh pr view <head>`
# races against GitHub's API propagation: the PR was just created but
# GET-by-head may return empty for hundreds of ms to seconds.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init mcprace

# CRITICAL: no `gh pr view` fixture. If the hook re-queries via gh, it
# gets empty stdout and writes nothing. Test passes only if the hook
# reads tool_response directly.
gh_fixture_reset

mcp_input() {
  jq -nc \
    --arg head "$1" \
    --arg cwd "$REPO" \
    --arg tx "$TX" \
    --argjson resp "$2" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head},
      cwd:$cwd,
      transcript_path:$tx,
      tool_response:($resp + {success:true})}'
}

# --- REST-API-style response (head.ref, base.ref, state="open" lowercase)
rest_resp='{"html_url":"https://example.com/pr/77","number":77,"state":"open","head":{"ref":"feat-mcp"},"base":{"ref":"develop"}}'
mcp_input feat-mcp "$rest_resp" | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-mcp\t'                "head written"
assert_contains "$row" "https://example.com/pr/77"   "url written"
assert_contains "$row" $'\tdevelop\t'                 "base written"
assert_contains "$row" $'\t77'                        "number written"
assert_file_exists "$CI_DIR/push-pending-$SK"
assert_file_contents "$CI_DIR/push-pending-$SK" "https://example.com/pr/77"

# Verify the hook did NOT invoke `gh pr view` (which would prove it read tool_response)
if [ -s "$GH_MOCK_LOG" ] && grep -q "^.*pr view" "$GH_MOCK_LOG"; then
  _fail "hook called gh pr view despite tool_response containing the data"
fi

# --- gh-style fields (baseRefName, headRefName, state="OPEN" uppercase)
rm -f "$STATE_DIR/$SK" "$CI_DIR/push-pending-$SK"
: > "$GH_MOCK_LOG"
gh_resp='{"url":"https://example.com/pr/88","number":88,"state":"OPEN","headRefName":"feat-gh","baseRefName":"main"}'
mcp_input feat-gh "$gh_resp" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-gh\t' "gh-style fields handled"
assert_contains "$row" $'\tmain\t'    "gh-style base"
if grep -q "pr view" "$GH_MOCK_LOG" 2>/dev/null; then
  _fail "hook re-queried via gh pr view (should have used tool_response)"
fi

# --- -cached suffix stripped from tool_response base
rm -f "$STATE_DIR/$SK"
cached_resp='{"html_url":"https://example.com/pr/99","number":99,"state":"open","head":{"ref":"feat-c"},"base":{"ref":"develop-cached"}}'
mcp_input feat-c "$cached_resp" | bash "$HOOK"
assert_equal "$(awk -F'\t' '$2 == "feat-c" {print $4}' "$STATE_DIR/$SK")" "develop" "-cached stripped"

# --- MERGED state in tool_response → row NOT written
rm -f "$STATE_DIR/$SK"
merged_resp='{"html_url":"https://example.com/pr/55","number":55,"state":"closed","head":{"ref":"feat-m"},"base":{"ref":"develop"}}'
mcp_input feat-m "$merged_resp" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# --- Fallback: tool_response missing required fields → falls back to gh pr view
rm -f "$STATE_DIR/$SK"
gh_fixture_pr feat-fallback OPEN develop 200
minimal_resp='{"id":"123"}'  # no url, no head — must fall back
mcp_input feat-fallback "$minimal_resp" | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-fallback\t' "fell back to gh pr view"

echo "mcp create uses tool_response ok"

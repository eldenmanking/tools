#!/bin/bash
# Option B: after a successful PR capture, the post-push hook emits a
# stderr line suggesting /babysit-ci. PostToolUse stderr is surfaced to
# Claude alongside the tool result, so the nudge fires immediately on
# the SAME turn as the push — not deferred to the next Stop boundary
# (which Claude often skips past during multi-step work).

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init nudge

# --- Path A: Bash `gh pr create` → nudge on stderr
gh_fixture_pr feat-A OPEN develop 100 "https://github.com/o/r/pull/100"
err=$(hook_input_bash "gh pr create -H feat-A" "$REPO" "$TX" | bash "$HOOK" 2>&1 >/dev/null)
assert_contains "$err" "[pr-statusline]"                              "Bash: nudge prefix"
assert_contains "$err" "tracked PR https://github.com/o/r/pull/100"  "Bash: URL surfaced"
assert_contains "$err" "/babysit-ci"                                  "Bash: babysit-ci suggested"
# Sanity: the URL appears twice (once as "tracked PR <url>", once as "/babysit-ci <url>")
url_count=$(echo "$err" | grep -oF "https://github.com/o/r/pull/100" | wc -l)
assert_equal "$url_count" "2" "Bash: URL surfaced for both label and command"

# --- Path B: MCP fast-path (full tool_response) → nudge
rm -f "$STATE_DIR/$SK"
gh_fixture_reset
mcp_full() {
  jq -nc --arg head "$1" --arg cwd "$REPO" --arg tx "$TX" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head,base:"develop"},
      cwd:$cwd, transcript_path:$tx,
      tool_response:{html_url:"https://github.com/o/r/pull/200",number:200,state:"open",
                     head:{ref:$head},base:{ref:"develop"},success:true}}'
}
err=$(mcp_full feat-B | bash "$HOOK" 2>&1 >/dev/null)
assert_contains "$err" "tracked PR https://github.com/o/r/pull/200" "MCP full: URL surfaced"
assert_contains "$err" "/babysit-ci https://github.com/o/r/pull/200" "MCP full: babysit-ci command line"

# --- Path C: MCP fast-path (minimal {id, url}) → nudge with the url from response
rm -f "$STATE_DIR/$SK"
mcp_min() {
  jq -nc --arg head "$1" --arg cwd "$REPO" --arg tx "$TX" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head,base:"develop",draft:false},
      cwd:$cwd, transcript_path:$tx,
      tool_response:{id:"42",url:"https://github.com/o/r/pull/300",success:true}}'
}
err=$(mcp_min feat-C | bash "$HOOK" 2>&1 >/dev/null)
assert_contains "$err" "tracked PR https://github.com/o/r/pull/300" "MCP minimal: nudge URL"

# --- Batched create → one nudge per PR
rm -f "$STATE_DIR/$SK"
gh_fixture_pr feat-D1 OPEN develop 401 "https://github.com/o/r/pull/401"
gh_fixture_pr feat-D2 OPEN develop 402 "https://github.com/o/r/pull/402"
err=$(hook_input_bash "gh pr create -H feat-D1 && gh pr create -H feat-D2" "$REPO" "$TX" | bash "$HOOK" 2>&1 >/dev/null)
assert_contains "$err" "https://github.com/o/r/pull/401" "batched: first PR nudged"
assert_contains "$err" "https://github.com/o/r/pull/402" "batched: second PR nudged"
# Two distinct nudges (count "tracked PR " occurrences)
nudge_count=$(echo "$err" | grep -cF "tracked PR ")
assert_equal "$nudge_count" "2" "batched: one nudge per captured PR"

# --- Negative: non-push commands produce no nudge
err=$(hook_input_bash "ls /tmp" "$REPO" "$TX" | bash "$HOOK" 2>&1 >/dev/null)
assert_equal "$err" "" "non-push: no nudge"

# --- Negative: gh pr create that errored (success=false) produces no nudge
err=$(hook_input_bash "gh pr create -H feat-A" "$REPO" "$TX" false | bash "$HOOK" 2>&1 >/dev/null)
assert_equal "$err" "" "failed create: no nudge"

# --- Negative: MCP with `success:false` produces no nudge
rm -f "$STATE_DIR/$SK"
err=$(echo '{"tool_name":"mcp__github__create_pull_request","tool_input":{"head":"feat-fail"},"cwd":"'"$REPO"'","transcript_path":"'"$TX"'","tool_response":{"success":false}}' | bash "$HOOK" 2>&1 >/dev/null)
assert_equal "$err" "" "MCP failed create: no nudge"

echo "babysit-ci nudge on capture ok"

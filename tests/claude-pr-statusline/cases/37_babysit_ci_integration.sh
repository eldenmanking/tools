#!/bin/bash
# Babysit-ci handshake regression guard. The chain is:
#   1. Hook writes URL to $CI_DIR/push-pending-<session_key>
#   2. stop-ci-check.sh `head -1`s the flag → $pr_url
#   3. Nudge tells Claude to "/babysit-ci $pr_url"
# Babysit-ci then parses the URL for owner/repo/number/hostname.
#
# After the MCP fast-path + env-var-prefix + slashed-branch changes, this
# pins that every hook path still feeds a PR URL that babysit-ci can
# consume (contains `/pull/<N>` or `/pulls/<N>`; no embedded newlines;
# parseable by Claude).

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init babysit

assert_nudge_has_url() {
  # Run stop hook, capture stderr, assert it surfaces $1 verbatim with
  # /babysit-ci suggestion. Works in both soft and strict modes.
  local expected="$1" msg="$2"
  local out
  set +e
  out=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
  set -e
  assert_contains "$out" "$expected"    "$msg: URL in soft nudge"
  assert_contains "$out" "/babysit-ci"  "$msg: /babysit-ci suggestion present"
  # Strict mode uses the same flag; spot-check the URL is also there.
  set +e
  out=$(printf '{"transcript_path":"%s"}\n' "$TX" | CLAUDE_PR_STATUSLINE_STRICT=1 bash "$STOP" 2>&1)
  set -e
  assert_contains "$out" "$expected"  "$msg: URL in strict nudge"
  assert_contains "$out" "/babysit-ci" "$msg: strict mentions /babysit-ci"
}

assert_url_is_parseable() {
  # Surrogate for "babysit-ci can extract owner/repo/number from this".
  # GitHub URLs (HTML or API) both contain `/pull(s)/<N>$`.
  local url="$1" msg="$2"
  printf '%s\n' "$url" | grep -qE '/pulls?/[0-9]+(\?|/?$)' \
    || _fail "$msg: URL is not a parseable PR URL: $url"
}

# --- Path A: Bash `gh pr create` (with realistic GHE URL shape)
gh_fixture_pr feat-A OPEN develop 100 "https://github.example.com/o/r/pull/100"
hook_input_bash "gh pr create -H feat-A" "$REPO" "$TX" | bash "$HOOK"
flag="$CI_DIR/push-pending-$SK"
assert_file_exists "$flag"
pr_url=$(head -1 "$flag")
assert_equal "$pr_url" "https://github.example.com/o/r/pull/100" "Bash create flag URL"
assert_url_is_parseable "$pr_url" "Bash create"
assert_nudge_has_url "$pr_url" "Bash create"

# --- Path B: Env-var-prefixed gh pr create (the original race report)
rm -f "$flag"
gh_fixture_reset
gh_fixture_pr feat-B OPEN develop 200 "https://github.example.com/o/r/pull/200"
hook_input_bash "GH_HOST=github.example.com gh pr create -H feat-B" "$REPO" "$TX" | bash "$HOOK"
pr_url=$(head -1 "$flag")
assert_equal "$pr_url" "https://github.example.com/o/r/pull/200" "env-var-prefixed create flag URL"
assert_nudge_has_url "$pr_url" "env-var prefix"

# --- Path C: MCP create with full tool_response (REST-style html_url)
rm -f "$flag" "$STATE_DIR/$SK"
gh_fixture_reset
mcp_full() {
  jq -nc --arg head "$1" --arg cwd "$REPO" --arg tx "$TX" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head,base:"develop"},
      cwd:$cwd, transcript_path:$tx,
      tool_response:{html_url:"https://github.example.com/o/r/pull/300",
                     url:"https://api.github.example.com/repos/o/r/pulls/300",
                     number:300,state:"open",
                     head:{ref:$head},base:{ref:"develop"},
                     success:true}}'
}
mcp_full feat-C | bash "$HOOK"
pr_url=$(head -1 "$flag")
assert_equal "$pr_url" "https://github.example.com/o/r/pull/300" "MCP full: prefers html_url"
assert_url_is_parseable "$pr_url" "MCP full"
assert_nudge_has_url "$pr_url" "MCP full"

# --- Path D: MCP create with minimal {id, url} (the user-reported race)
rm -f "$flag" "$STATE_DIR/$SK"
mcp_min() {
  jq -nc --arg head "$1" --arg cwd "$REPO" --arg tx "$TX" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head,base:"develop",draft:false},
      cwd:$cwd, transcript_path:$tx,
      tool_response:{id:"12889199",
                     url:"https://github.example.com/o/r/pull/400",
                     success:true}}'
}
mcp_min feat-D | bash "$HOOK"
pr_url=$(head -1 "$flag")
assert_equal "$pr_url" "https://github.example.com/o/r/pull/400" "MCP minimal: url from tool_response"
assert_url_is_parseable "$pr_url" "MCP minimal"
assert_nudge_has_url "$pr_url" "MCP minimal"

# --- Path E: Slashed branch (the cache-path bug case)
rm -f "$flag" "$STATE_DIR/$SK"
SLASH="andywang/meticulous-feature"
gh_fixture_pr "$SLASH" OPEN develop 500 "https://github.example.com/o/r/pull/500"
hook_input_bash "gh pr create -H $SLASH" "$REPO" "$TX" | bash "$HOOK"
pr_url=$(head -1 "$flag")
assert_equal "$pr_url" "https://github.example.com/o/r/pull/500" "slashed branch flag URL"
assert_nudge_has_url "$pr_url" "slashed branch"

# --- Flag survives until refresh-core clears it (babysit-ci runs in background;
#     /refresh-pr-state is the step that clears the flag in the lifecycle)
assert_file_exists "$flag"  # still present after stop nudge
printf '' | bash "$REFRESH" "$STATE_DIR/$SK" > /dev/null
assert_file_missing "$flag"  # refresh clears the flag

# --- After clear, stop hook is silent (no babysit-ci nudge)
set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
set -e
assert_equal "$out" "" "no nudge after flag clear"

echo "babysit-ci integration ok"

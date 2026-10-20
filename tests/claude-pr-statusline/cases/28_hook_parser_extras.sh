#!/bin/bash
# Round-1 bug fixes + previously-uncovered parser paths:
#   - semicolon as sub-command separator
#   - subshell parens around `cd && gh pr create`
#   - cd then ; then another gh pr create
#   - `gh api -XPOST` (no space)
#   - `gh api --method=POST`
#   - MCP create_pull_request with tool_response.success=false

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init extras

gh_fixture_pr feat-x OPEN develop 100
gh_fixture_pr feat-y OPEN develop 101

run_hook() {
  rm -f "$STATE_DIR/$SK"
  hook_input_bash "$1" "${2:-$REPO}" "$TX" | bash "$HOOK"
}

# --- ; separator
run_hook "gh pr create -H feat-x ; gh pr create -H feat-y"
assert_equal "$(wc -l < "$STATE_DIR/$SK")" "2" "; separator captures both"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-x\t' "first head after ;"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-y\t' "second head after ;"

# --- Subshell parens
run_hook "(gh pr create -H feat-x)"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "subshell parens stripped"

OTHER="$SBX/other-repo"
mkdir -p "$OTHER"
(cd "$OTHER" && git init -q -b main && git config user.email t@t && git config user.name t && git -c commit.gpgsign=false commit -q --allow-empty -m init)
run_hook "(cd $OTHER && gh pr create -H feat-x)" "$REPO"
row=$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)
assert_contains "$row" "$OTHER" "subshell parens preserve cd-override"

# --- gh api with -XPOST (no space)
run_hook "gh api -XPOST /repos/o/r/pulls -f head=feat-x -f base=develop"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "-XPOST captured"

# --- gh api with --method=POST
run_hook "gh api --method=POST /repos/o/r/pulls -f head=feat-x -f base=develop"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "--method=POST captured"

# --- MCP create with tool_response.success=false short-circuits the hook
rm -f "$STATE_DIR/$SK"
echo '{"tool_name":"mcp__github__create_pull_request","tool_input":{"head":"feat-x"},"cwd":"'"$REPO"'","transcript_path":"'"$TX"'","tool_response":{"success":false}}' \
  | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

echo "round-1 parser extras ok"

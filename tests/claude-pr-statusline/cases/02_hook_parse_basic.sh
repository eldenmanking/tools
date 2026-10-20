#!/bin/bash
# post-push-ci.sh basic parsing paths: `gh pr create -H X`, `--head X`,
# MCP create_pull_request, and the fallback-to-current-branch case.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init basic

gh_fixture_pr feat-x OPEN develop 100

# --- gh pr create -H feat-x
hook_input_bash "gh pr create -H feat-x" "$REPO" "$TX" | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" "$REPO" "repo_root"
assert_contains "$row" $'\tfeat-x\t' "branch field"
assert_contains "$row" "https://example.com/pr/100" "pr_url"
assert_contains "$row" $'\tdevelop\t' "base_branch"
assert_contains "$row" $'\t100' "number"
assert_file_exists "$CI_DIR/push-pending-$SK"
assert_file_contents "$CI_DIR/push-pending-$SK" "https://example.com/pr/100"

# --- gh pr create --head feat-x (long form)
rm "$STATE_DIR/$SK"
hook_input_bash "gh pr create --head feat-x" "$REPO" "$TX" | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-x\t' "long --head form"

# --- MCP create_pull_request
rm "$STATE_DIR/$SK"
hook_input_mcp_create "feat-x" "$REPO" "$TX" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-x\t' "mcp create"
assert_contains "$row" "https://example.com/pr/100" "mcp pr_url"

# --- git push falls back to current branch
rm -f "$STATE_DIR/$SK"
(cd "$REPO" && git checkout -q -b feat-x)
hook_input_bash "git push" "$REPO" "$TX" | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-x\t' "git push fallback to current branch"

# --- Non-`is_push` tool calls produce no state
rm -f "$STATE_DIR/$SK" "$CI_DIR/push-pending-$SK"
hook_input_bash "ls /tmp" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# --- Dedup: same (repo, branch) replaces, doesn't duplicate
hook_input_bash "gh pr create -H feat-x" "$REPO" "$TX" | bash "$HOOK"
hook_input_bash "gh pr create -H feat-x" "$REPO" "$TX" | bash "$HOOK"
assert_equal "$(wc -l < "$STATE_DIR/$SK")" "1" "dedup on (repo, branch)"

echo "all hook-basic checks ok"

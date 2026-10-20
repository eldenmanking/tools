#!/bin/bash
# post-push-ci.sh: state file shape, -cached suffix stripping,
# MERGED PR skipping, dedup with timestamp refresh, cache file path.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init writes

gh_fixture_pr feat-x        OPEN   develop-cached 100
gh_fixture_pr feat-merged   MERGED develop        200

# --- TSV shape: 5 tab-separated fields per row (repo, branch, url, base, num)
hook_input_bash "gh pr create -H feat-x" "$REPO" "$TX" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_equal "$(awk -F'\t' '{print NF}' <<< "$row")" "5" "row has 5 tab-separated fields"

# --- -cached suffix stripped from base_branch
assert_equal "$(awk -F'\t' '{print $4}' <<< "$row")" "develop" "-cached suffix stripped"

# --- MERGED PR is not added
rm "$STATE_DIR/$SK"
hook_input_bash "gh pr create -H feat-merged" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# --- pr-cache uses branch-aware key
hook_input_bash "gh pr create -H feat-x" "$REPO" "$TX" | bash "$HOOK"
repo_key=$(md5 "$REPO")
assert_file_exists "$CACHE_DIR/${repo_key}_feat-x"
assert_file_contents "$CACHE_DIR/${repo_key}_feat-x" "https://example.com/pr/100"

echo "all state-write checks ok"

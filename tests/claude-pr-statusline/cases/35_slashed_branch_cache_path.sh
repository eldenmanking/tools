#!/bin/bash
# Cache path bug: branch names commonly contain `/` (`andywang/feature`).
# The cache file path `<md5(repo)>_<branch>` interprets the slash as a
# subdirectory, so writes fail with "No such file or directory" — and
# subsequent lookups miss. The fix is to sanitize the branch before
# using it in any path.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init slashbr

BRANCH="andywang/meticulous-feature"

# --- Hook write path (gh pr create)
gh_fixture_pr "$BRANCH" OPEN develop 42
hook_err="$SBX/hook.err"
hook_input_bash "gh pr create -H $BRANCH" "$REPO" "$TX" | bash "$HOOK" 2>"$hook_err"
if [ -s "$hook_err" ] && grep -q "No such file or directory" "$hook_err"; then
  _fail "hook leaked 'No such file or directory' to stderr"
fi

# State file got the row
assert_file_exists "$STATE_DIR/$SK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\t'"$BRANCH"$'\t' "row written with slashed branch"

# Cache file exists under a sanitized name (no subdir created)
repo_key=$(md5 "$REPO")
# Sanitized key: '/' → '_'
sanitized="${BRANCH//\//_}"
assert_file_exists "$CACHE_DIR/${repo_key}_${sanitized}"
# AND no junk subdir was created
[ ! -d "$CACHE_DIR/${repo_key}_andywang" ] || _fail "cache subdir created — branch not sanitized"

# --- Statusline render: lookup must find the same cache file
(cd "$REPO" && git checkout -q -b "$BRANCH")
# Wipe state so legacy fallback path is used (exercises pr_cache_lookup)
rm "$STATE_DIR/$SK"
out=$(render_status)
assert_contains "$out" "https://example.com/pr/42" "cache lookup hit for slashed branch"

echo "slashed branch cache path ok"

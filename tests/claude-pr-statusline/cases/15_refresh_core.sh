#!/bin/bash
# refresh-pr-state-core: re-queries existing rows, drops merged/closed,
# adds new rows from stdin (pr_url\trepo_root), refreshes base_branch,
# preserves rows on gh transport failure.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init refresh

STATE="$STATE_DIR/$SK"

seed_state_row "$REPO" feat-1       https://example.com/pr/1 develop 1
seed_state_row "$REPO" feat-2       https://example.com/pr/2 feat-1  2
seed_state_row "$REPO" feat-merged  https://example.com/pr/3 develop 3

# Push-pending flag is cleared by refresh.
touch "$CI_DIR/push-pending-$SK"

gh_fixture_pr_url https://example.com/pr/1 OPEN   develop        feat-1       1
gh_fixture_pr_url https://example.com/pr/2 DRAFT  feat-1         feat-2       2
gh_fixture_pr_url https://example.com/pr/3 MERGED develop        feat-merged  3
gh_fixture_pr_url https://example.com/pr/4 OPEN   develop-cached feat-new     4

# Add a new PR from stdin
out=$(printf 'https://example.com/pr/4\t%s\n' "$REPO" | bash "$REFRESH" "$STATE")

# Result: feat-1, feat-2, feat-new (3 rows, merged dropped)
assert_equal "$(wc -l < "$STATE")" "3" "rows after refresh"
contents=$(cat "$STATE")
assert_contains "$contents" "feat-1"   "kept feat-1"
assert_contains "$contents" "feat-2"   "kept feat-2"
assert_contains "$contents" "feat-new" "added feat-new"
assert_not_contains "$contents" "feat-merged" "dropped merged"
assert_equal "$(awk -F'\t' '$2 == "feat-new" {print $4}' "$STATE")" "develop" "-cached stripped from new row"
assert_file_missing "$CI_DIR/push-pending-$SK"
assert_contains "$out" "refresh:" "summary printed"

# --- Re-querying refreshes base_branch.
gh_fixture_reset
gh_fixture_pr_url https://example.com/pr/1 OPEN  main   feat-1   1
gh_fixture_pr_url https://example.com/pr/2 DRAFT feat-1 feat-2   2
gh_fixture_pr_url https://example.com/pr/4 OPEN  develop feat-new 4
printf '' | bash "$REFRESH" "$STATE" > /dev/null
assert_equal "$(awk -F'\t' '$2 == "feat-1" {print $4}' "$STATE")" "main" "base_branch refreshed"

# --- Transport failure preserves rows.
cat > "$STATE" <<EOF
$REPO	feat-1	https://example.com/pr/1	develop	1
$REPO	feat-2	https://example.com/pr/2	feat-1	2
EOF
gh_fixture_reset
gh_fixture_raw "pr view https://example.com/pr/1 --json url,baseRefName,headRefName,number,state" "" 4
gh_fixture_raw "pr view https://example.com/pr/2 --json url,baseRefName,headRefName,number,state" "" 4
out=$(printf '' | bash "$REFRESH" "$STATE")
assert_equal "$(wc -l < "$STATE")" "2" "rows preserved on gh transport failure"
assert_contains "$out" "preserved" "summary flags preserved rows"

# --- gh returns exit 0 with empty stdout → row dropped as "unreachable"
cat > "$STATE" <<EOF
$REPO	feat-empty	https://example.com/pr/9	develop	9
EOF
gh_fixture_reset
gh_fixture_raw "pr view https://example.com/pr/9 --json url,baseRefName,headRefName,number,state" "" 0
out=$(printf '' | bash "$REFRESH" "$STATE")
assert_equal "$(wc -l < "$STATE")" "0" "empty-stdout PR row dropped"
assert_contains "$out" "unreachable" "summary mentions unreachable"

echo "refresh-pr-state-core ok"

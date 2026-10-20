#!/bin/bash
# discover-pr-state-core: walks up via base→head (single match), down via
# head→base (multi OK), strips -cached, bails on ambiguous up-walks.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init disc

STATE="$STATE_DIR/$SK"

# Seed: middle PR feat-2. Stack: develop ← feat-1 ← feat-2 ← {feat-3, feat-3b}.
seed_state_row "$REPO" feat-2 https://example.com/pr/2 feat-1 2

gh_fixture_list_head feat-1 https://example.com/pr/1 feat-1 develop 1
gh_fixture_list_empty head develop
gh_fixture_list_base feat-2 \
  "https://example.com/pr/3|feat-3|feat-2|3" \
  "https://example.com/pr/3b|feat-3b|feat-2|31"
gh_fixture_list_empty base feat-3
gh_fixture_list_empty base feat-3b

out=$(printf '' | bash "$DISCOVER" "$STATE")

# Result: feat-1, feat-2, feat-3, feat-3b (4 rows)
assert_equal "$(wc -l < "$STATE")" "4" "rows after discovery"
contents=$(cat "$STATE")
assert_contains "$contents" $'\tfeat-1\t'  "walked up to feat-1"
assert_contains "$contents" $'\tfeat-3\t'  "walked down to feat-3"
assert_contains "$contents" $'\tfeat-3b\t' "walked down to sibling feat-3b"
assert_contains "$out"      "added=3"      "summary shows 3 added"

# --- Ambiguous up-walk bails (multiple matches)
gh_fixture_reset
echo > "$STATE"
seed_state_row "$REPO" feat-2 https://example.com/pr/2 feat-x 2
# Manually craft a multi-element list response
gh_fixture_raw "pr list --head feat-x --state open --json url,baseRefName,headRefName,number" \
  '[{"url":"https://example.com/pr/a","baseRefName":"develop","headRefName":"feat-x","number":7},{"url":"https://example.com/pr/b","baseRefName":"main","headRefName":"feat-x","number":8}]'
gh_fixture_list_empty base feat-2
out=$(printf '' | bash "$DISCOVER" "$STATE")
assert_not_contains "$(cat "$STATE")" $'\tfeat-x\thttps://' "ambiguous up-walk did not add"
assert_contains "$out" "up-ambig" "bail recorded"

# --- -cached suffix stripped at discovery
gh_fixture_reset
echo > "$STATE"
seed_state_row "$REPO" feat-2 https://example.com/pr/2 feat-1-cached 2
gh_fixture_list_head feat-1 https://example.com/pr/1 feat-1 develop-cached 1
gh_fixture_list_empty head develop
gh_fixture_list_empty base feat-2
printf '' | bash "$DISCOVER" "$STATE" > /dev/null
assert_equal "$(awk -F'\t' '$2 == "feat-1" {print $4}' "$STATE")" "develop" "-cached stripped on added row"

# --- Stdin seed picks up a PR not in existing state
gh_fixture_reset
: > "$STATE"
gh_fixture_list_empty head develop
gh_fixture_list_empty base feat-seed
printf '%s\tfeat-seed\thttps://example.com/pr/seed\tdevelop\t99\n' "$REPO" \
  | bash "$DISCOVER" "$STATE" > /dev/null
assert_contains "$(cat "$STATE")" "feat-seed" "stdin seed ingested"

echo "discover-pr-state-core ok"

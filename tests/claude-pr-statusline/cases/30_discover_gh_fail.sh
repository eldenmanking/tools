#!/bin/bash
# discover-core: gh transport failure during walk_up / walk_down records a
# `gh-fail(...)` bail in the summary (rather than silently returning
# added=0).

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init ghfail

STATE="$STATE_DIR/$SK"
seed_state_row "$REPO" feat-mid https://example.com/pr/50 feat-parent 50

# walk_up calls `gh pr list --head feat-parent`. Fixture returns exit 4
# (auth/transport). walk_down on feat-mid stays empty.
gh_fixture_raw "pr list --head feat-parent --state open --json url,baseRefName,headRefName,number" "" 4
gh_fixture_list_empty base feat-mid

out=$(printf '' | bash "$DISCOVER" "$STATE")

# State unchanged (single original row preserved)
assert_equal "$(wc -l < "$STATE")" "1" "no new rows on gh failure"
# Summary surfaces the bail
assert_contains "$out" "gh-fail(up:feat-parent)" "gh-fail bail recorded for walk_up"

# --- walk_down failure: seed pushes us to walk_down feat-mid. Make THAT fail.
gh_fixture_reset
gh_fixture_list_empty head feat-parent  # walk_up returns nothing → fine
gh_fixture_raw "pr list --base feat-mid --state open --json url,baseRefName,headRefName,number" "" 4
out=$(printf '' | bash "$DISCOVER" "$STATE")
assert_contains "$out" "gh-fail(down:feat-mid)" "gh-fail bail recorded for walk_down"

echo "discover gh-fail bail ok"

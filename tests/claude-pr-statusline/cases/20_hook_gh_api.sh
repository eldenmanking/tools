#!/bin/bash
# post-push-ci.sh `gh api -X POST .../pulls -f head=X` parser path.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init api

gh_fixture_pr feat-api OPEN develop 77

# -X POST + /pulls + -f head=...
hook_input_bash "gh api -X POST /repos/o/r/pulls -f head=feat-api -f base=develop -f title=t" "$REPO" "$TX" | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tfeat-api\t' "gh api POST .../pulls captured"

# PATCH to /pulls (not a create)
rm -f "$STATE_DIR/$SK"
hook_input_bash "gh api -X PATCH /repos/o/r/pulls/100 -f base=develop" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

# POST to non-/pulls endpoint
rm -f "$STATE_DIR/$SK"
hook_input_bash "gh api -X POST /repos/o/r/issues -f title=t" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

echo "gh api parser ok"

#!/bin/bash
# Auto-seed: legacy fallback writes a state row when current branch has
# an open PR. Opt out with CLAUDE_PR_STATUSLINE_AUTOSEED=0.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init seed

# Two fixtures: the cache-fill lookup (`gh pr view <branch> --json url`) and
# the full lookup the auto-seed path makes.
gh_fixture_raw "pr view feat-seed --json url -q .url" "https://example.com/pr/777"
gh_fixture_pr feat-seed OPEN develop 777

(cd "$REPO" && git checkout -q -b feat-seed)

# First render: fallback + auto-seed
out=$(render_status)
assert_contains "$out" "https://example.com/pr/777" "fallback rendered URL"
assert_file_exists "$STATE_DIR/$SK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-seed\t'              "auto-seed row written"
assert_contains "$row" "https://example.com/pr/777"  "auto-seed URL recorded"

# Second render: multi-line view
out2=$(render_status)
assert_contains "$out2" "▶"         "second render promoted to multi-line"
assert_contains "$out2" "feat-seed" "feat-seed visible"

# --- Opt-out: AUTOSEED=0 keeps state file empty
test_init noseed
gh_fixture_raw "pr view feat-noseed --json url -q .url" "https://example.com/pr/888"
gh_fixture_pr feat-noseed OPEN develop 888
(cd "$REPO" && git checkout -q -b feat-noseed)
CLAUDE_PR_STATUSLINE_AUTOSEED=0 render_status > /dev/null
assert_file_missing "$STATE_DIR/$SK"

echo "auto-seed ok"

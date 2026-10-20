#!/bin/bash
# Round-1 fix: pr-cache `.checked` sentinel must NOT stick after a gh
# failure. Previous behavior: writing the marker before gh was called
# made the negative cache permanent even when the failure was a network
# blip. After the fix, the sentinel is only touched on gh exit-0 with
# empty stdout.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init offline

(cd "$REPO" && git checkout -q -b feat-new)

# --- First render: gh fails (no fixture matched → exit 0 empty, simulating
#     "PR genuinely doesn't exist"). Sentinel is created legitimately.
render_status > /dev/null
key=$(md5 "$REPO")
assert_file_exists "$CACHE_DIR/${key}_feat-new.checked"
assert_file_missing "$CACHE_DIR/${key}_feat-new"

# --- Reset cache, this time gh fails with exit 4 (network/auth). Sentinel
#     should NOT be written; next render gets to retry.
rm -f "$CACHE_DIR/${key}_feat-new" "$CACHE_DIR/${key}_feat-new.checked"
gh_fixture_raw "pr view feat-new --json url -q .url" "" 4
render_status > /dev/null
assert_file_missing "$CACHE_DIR/${key}_feat-new"
assert_file_missing "$CACHE_DIR/${key}_feat-new.checked"

# --- Restore connectivity. The next render should find the PR.
gh_fixture_reset
gh_fixture_raw "pr view feat-new --json url -q .url" "https://example.com/pr/42"
gh_fixture_pr feat-new OPEN develop 42
render_status > /dev/null
assert_file_exists "$CACHE_DIR/${key}_feat-new"
assert_file_contents "$CACHE_DIR/${key}_feat-new" "https://example.com/pr/42"

echo "pr-cache offline recovery ok"

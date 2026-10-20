#!/bin/bash
# Current (repo, branch) not in tracked stack → renders below with blank-line
# separator. Should NOT have a ▶ on any tracked row.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init unrelated

seed_state_row "$REPO" feat-1 https://example.com/pr/1 develop 1

(cd "$REPO" && git checkout -q -b totally-different)
out=$(render_status)

# Two ▶ would be a bug — only the current block gets one.
marker_count=$(printf '%s' "$out" | tr -cd '▶' | wc -c)
marker_count=$((marker_count / 3))  # UTF-8 3 bytes per ▶
assert_equal "$marker_count" "1" "exactly one current marker"

assert_contains "$out" "feat-1"            "tracked row visible"
assert_contains "$out" "totally-different" "current branch visible"

# Blank line separator between stack and current block
blank_count=$(printf '%s\n' "$out" | grep -c '^$' || true)
[ "$blank_count" -ge 1 ] || _fail "expected at least one blank line separator"

echo "unrelated-current ok"

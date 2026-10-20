#!/bin/bash
# Statusline with a tracked stack: depth-based ordering, current row marked.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init tracked

# 3-PR stack: feat-1 ← feat-2 ← feat-3. Inserted out of order; depth sort
# should reorder to feat-1, feat-2, feat-3.
seed_state_row "$REPO" feat-3 https://example.com/pr/3 feat-2  3
seed_state_row "$REPO" feat-1 https://example.com/pr/1 develop 1
seed_state_row "$REPO" feat-2 https://example.com/pr/2 feat-1  2

(cd "$REPO" && git checkout -q -b feat-2)
out=$(render_status)

lines=$(printf '%s\n' "$out" | grep -E '(feat-)')
first=$(printf  '%s\n' "$lines" | sed -n '1p')
second=$(printf '%s\n' "$lines" | sed -n '2p')
third=$(printf  '%s\n' "$lines" | sed -n '3p')
assert_contains "$first"  "  feat-1  " "first row is feat-1 (column-bounded)"
assert_contains "$second" "  feat-2  " "second row is feat-2 (column-bounded)"
assert_contains "$third"  "  feat-3  " "third row is feat-3 (column-bounded)"

# Current marker on feat-2
assert_contains "$second" "▶"     "feat-2 marked as current"
assert_not_contains "$first"  "▶" "feat-1 not marked"
assert_not_contains "$third"  "▶" "feat-3 not marked"

echo "stack sort ok"

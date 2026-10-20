#!/bin/bash
# Data fields containing backslash escapes must NOT be interpreted as
# printf %b escape sequences.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init escape

seed_state_row "$REPO" 'feat\nfoo' https://example.com/pr/1 develop 1

(cd "$REPO" && git checkout -q -b unrelated)
out=$(render_status)

# The two characters \ and n must appear literally somewhere
assert_contains "$out" 'feat\nfoo' "branch with literal backslash-n preserved"

# A real %b bug would have INJECTED a newline mid-line. Expect ≤4 lines:
# tracked row, blank separator, current block, ctx%.
[ "$(printf '%s\n' "$out" | wc -l)" -le 4 ] || _fail "spurious newline injected"

echo "printf escape ok"

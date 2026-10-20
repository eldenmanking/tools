#!/bin/bash
# When tracked PRs exceed MAX_ROWS, render top (MAX_ROWS-1) and a "+N more"
# line. The current row must always be visible — even if it would be hidden.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init cap

# 15 unrelated PRs (no stacking)
for i in $(seq 1 15); do
  seed_state_row "$REPO" "feat-$i" "https://example.com/pr/$i" develop "$i"
done

(cd "$REPO" && git checkout -q -b feat-12)

# Default cap is 10
out=$(CLAUDE_STATUSLINE_MAX_ROWS=10 render_status)

# feat-12 (current) must appear, column-bounded
assert_contains "$out" "  feat-12  " "current row preserved"
# "+N more" line shows remaining count (digit before "more")
printf '%s\n' "$out" | grep -qE '\+[0-9]+ more' || _fail "expected '+<digits> more' line"
# Should not exceed cap rows + ctx line
[ "$(printf '%s\n' "$out" | wc -l)" -le 12 ] || _fail "too many lines"

# Smaller cap
out=$(CLAUDE_STATUSLINE_MAX_ROWS=3 render_status)
assert_contains "$out" "  feat-12  " "current row preserved at small cap"

echo "vertical cap ok"

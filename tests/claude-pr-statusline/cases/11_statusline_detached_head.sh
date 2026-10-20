#!/bin/bash
# Detached HEAD: cur_branch resolves to a short SHA; statusline still renders.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init det

(cd "$REPO" && git -c commit.gpgsign=false commit -q --allow-empty -m two && git checkout -q --detach HEAD)
out=$(render_status)

assert_contains "$out" "42%" "rendered with ctx"
printf '%s' "$out" | grep -qE '[0-9a-f]{7}|no branch' || _fail "expected short SHA or 'no branch' marker"

echo "detached head ok"

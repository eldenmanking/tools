#!/bin/bash
# Statusline legacy fallback: no session state file → single-line view.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init legacy

(cd "$REPO" && git checkout -q -b feat-x)

# No PR cached, no gh fixture → renders without PR
out=$(render_status)
assert_contains "$out" "feat-x" "current branch shown"
assert_contains "$out" "42%" "context pct shown"

# Cache pre-populated with a PR URL → URL appears
mkdir -p "$CACHE_DIR"
echo "https://example.com/pr/1" > "$CACHE_DIR/$(md5 "$REPO")_feat-x"
out=$(render_status)
assert_contains "$out" "https://example.com/pr/1" "cached PR shown"

echo "legacy view ok"

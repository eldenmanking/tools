#!/bin/bash
# Round-2 defensive read: stop-ci-check.sh reads flag with `head -1` so a
# multi-line flag file (corrupt / partial write) doesn't leak extra lines
# into the stderr nudge.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init multiflag

FLAG="$CI_DIR/push-pending-$SK"

# Write a multi-line flag (would surface extra noise without head -1)
printf 'https://example.com/pr/1\nhttps://example.com/pr/2\nhttps://example.com/pr/3\n' > "$FLAG"

set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
rc=$?
set -e

assert_equal "$rc" "0" "stop hook exits 0 in soft mode"
assert_contains     "$out" "https://example.com/pr/1" "first URL shown"
assert_not_contains "$out" "https://example.com/pr/2" "second URL NOT leaked"
assert_not_contains "$out" "https://example.com/pr/3" "third URL NOT leaked"

# Strict mode should also see only line 1
set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | CLAUDE_PR_STATUSLINE_STRICT=1 bash "$STOP" 2>&1)
set -e
assert_contains     "$out" "https://example.com/pr/1" "strict: first URL shown"
assert_not_contains "$out" "https://example.com/pr/2" "strict: second URL NOT leaked"

echo "stop hook multi-line flag ok"

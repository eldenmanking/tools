#!/bin/bash
# Stop hook: soft default, strict opt-in, TTL auto-expiry.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init stop

FLAG="$CI_DIR/push-pending-$SK"

# --- No flag → silent exit 0
set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
rc=$?
set -e
assert_equal "$rc"  "0" "no-flag → exit 0"
assert_equal "$out" ""  "no-flag → no output"

# --- Flag present, soft default → exit 0 + stderr
echo "https://example.com/pr/9" > "$FLAG"
set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | bash "$STOP" 2>&1)
rc=$?
set -e
assert_equal    "$rc"  "0"                       "soft mode → exit 0"
assert_contains "$out" "https://example.com/pr/9" "soft mode shows URL"
assert_contains "$out" "/babysit-ci"              "soft mode names /babysit-ci"
assert_file_exists "$FLAG"  # not cleared by soft mode

# --- Strict opt-in → exit 2
set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | CLAUDE_PR_STATUSLINE_STRICT=1 bash "$STOP" 2>&1)
rc=$?
set -e
assert_equal    "$rc"  "2"     "strict mode → exit 2"
assert_contains "$out" "MUST"  "strict mode demands action"

# --- TTL: backdate flag past TTL → auto-cleared + silent
echo "https://example.com/pr/old" > "$FLAG"
touch -d "3 hours ago" "$FLAG"
set +e
out=$(printf '{"transcript_path":"%s"}\n' "$TX" | CLAUDE_PR_STATUSLINE_FLAG_TTL=3600 bash "$STOP" 2>&1)
rc=$?
set -e
assert_equal "$rc"  "0" "stale flag → exit 0"
assert_equal "$out" ""  "stale flag → no output"
assert_file_missing "$FLAG"

echo "stop hook modes ok"

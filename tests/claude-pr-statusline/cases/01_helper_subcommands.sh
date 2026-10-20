#!/bin/bash
# pr-state.sh: state-dir / ci-dir / state-file / write-rows / drop-state /
# clear-flag / prune-pointers. Guard rails: refuse paths outside state-dir,
# refuse malformed session keys, refuse `..` escape.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init

# --- state-dir / ci-dir print correct paths
assert_equal "$(bash "$HELPER" state-dir)" "$STATE_DIR" "state-dir output"
assert_equal "$(bash "$HELPER" ci-dir)" "$CI_DIR" "ci-dir output"
[ -d "$STATE_DIR/_by_workspace" ] || _fail "_by_workspace dir not created"

# --- state-file without a pointer returns empty
cd "$HOME"
assert_equal "$(bash "$HELPER" state-file)" "" "state-file with no pointer must be empty"

# --- state-file with a valid pointer returns the path
ws_key=$(md5 "$HOME")
echo "abc123" > "$STATE_DIR/_by_workspace/$ws_key"
assert_equal "$(cd "$HOME" && bash "$HELPER" state-file)" "$STATE_DIR/abc123" "state-file with pointer"

# --- malformed pointer (slash) is ignored
echo "../etc/passwd" > "$STATE_DIR/_by_workspace/$ws_key"
assert_equal "$(cd "$HOME" && bash "$HELPER" state-file)" "" "state-file with slash in pointer is ignored"

# --- write-rows replaces target atomically
echo "abc123" > "$STATE_DIR/_by_workspace/$ws_key"
target="$STATE_DIR/abc123"
printf 'row1\nrow2\n' | bash "$HELPER" write-rows "$target"
assert_file_contents "$target" $'row1\nrow2' "write-rows output"

# --- write-rows refuses paths outside state-dir
if printf 'evil\n' | bash "$HELPER" write-rows "/tmp/evil-state" 2>/dev/null; then
  _fail "write-rows must refuse paths outside state-dir"
fi
[ ! -f /tmp/evil-state ] || { _fail "evil file written"; rm -f /tmp/evil-state; }

# --- write-rows refuses paths with `..` even under state-dir
if printf 'evil\n' | bash "$HELPER" write-rows "$STATE_DIR/../etc/escape" 2>/dev/null; then
  _fail "write-rows must refuse .. paths"
fi
[ ! -f "$HOME/.local/state/claude/etc/escape" ] || { _fail "escape file written"; rm -f "$HOME/.local/state/claude/etc/escape"; }

# --- drop-state removes file under state-dir
bash "$HELPER" drop-state "$target"
assert_file_missing "$target"

# --- drop-state refuses path outside state-dir
touch /tmp/keep-me-evil
if bash "$HELPER" drop-state "/tmp/keep-me-evil" 2>/dev/null; then
  _fail "drop-state must refuse paths outside state-dir"
fi
assert_file_exists "/tmp/keep-me-evil"
rm -f /tmp/keep-me-evil

# --- clear-flag removes push-pending-<key>
mkdir -p "$CI_DIR"
touch "$CI_DIR/push-pending-mykey"
bash "$HELPER" clear-flag mykey
assert_file_missing "$CI_DIR/push-pending-mykey"

# --- clear-flag refuses malformed key
touch "$CI_DIR/push-pending-mykey"
if bash "$HELPER" clear-flag "../escape" 2>/dev/null; then
  _fail "clear-flag must refuse ../"
fi

# --- prune-pointers drops pointer whose target session file is gone
echo "ghost" > "$STATE_DIR/_by_workspace/$ws_key"
assert_file_missing "$STATE_DIR/ghost"
bash "$HELPER" prune-pointers
assert_file_missing "$STATE_DIR/_by_workspace/$ws_key"

# --- prune-pointers keeps pointer whose target exists
printf 'row\n' | bash "$HELPER" write-rows "$STATE_DIR/realsession"
echo "realsession" > "$STATE_DIR/_by_workspace/$ws_key"
bash "$HELPER" prune-pointers
assert_file_exists "$STATE_DIR/_by_workspace/$ws_key"

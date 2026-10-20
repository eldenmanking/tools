#!/bin/bash
# Per-session marker dir: concurrent sessions in the same PWD each leave
# their own marker — no collision. state-file resolves to whichever
# rendered most recently.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init

tx_a=$(mk_session sess-a); sk_a=$(session_key_of "$tx_a")
tx_b=$(mk_session sess-b); sk_b=$(session_key_of "$tx_b")

statusline_input "$REPO" "$tx_a" | bash "$SL" > /dev/null
sleep 1  # mtime separation
statusline_input "$REPO" "$tx_b" | bash "$SL" > /dev/null

ws_dir="$STATE_DIR/_by_workspace/$(md5 "$REPO")"
[ -d "$ws_dir" ] || _fail "ws_dir not a directory: $ws_dir"

# Both markers exist — neither overwrote the other
assert_file_exists "$ws_dir/$sk_a"
assert_file_exists "$ws_dir/$sk_b"

# state-file picks the most-recently-touched marker (sess-b)
assert_equal "$(cd "$REPO" && bash "$HELPER" state-file)" "$STATE_DIR/$sk_b" "state-file picks most-recent marker"

# Re-render session A → mtime updates, state-file now picks A
sleep 1
statusline_input "$REPO" "$tx_a" | bash "$SL" > /dev/null
assert_equal "$(cd "$REPO" && bash "$HELPER" state-file)" "$STATE_DIR/$sk_a" "state-file follows most-recent renderer"

# --- Legacy compatibility: pre-existing single-file pointer still resolves,
#     and statusline migrates it to a marker dir on next render.
mkdir -p "$SBX/legacy-repo"
(cd "$SBX/legacy-repo" && git init -q -b main && git config user.email t@t && git config user.name t && git -c commit.gpgsign=false commit -q --allow-empty -m init)
ws_key2=$(md5 "$SBX/legacy-repo")
echo "legacy-session-key" > "$STATE_DIR/_by_workspace/$ws_key2"
: > "$STATE_DIR/legacy-session-key"  # session file must exist for state-file to return path
assert_equal "$(cd "$SBX/legacy-repo" && bash "$HELPER" state-file)" "$STATE_DIR/legacy-session-key" "legacy pointer still resolves"

# Render in legacy workspace → migrates to marker dir
tx_c=$(mk_session legacy); sk_c=$(session_key_of "$tx_c")
statusline_input "$SBX/legacy-repo" "$tx_c" | bash "$SL" > /dev/null
[ -d "$STATE_DIR/_by_workspace/$ws_key2" ] || _fail "legacy pointer was not migrated to dir"

echo "marker dir collision-free ok"

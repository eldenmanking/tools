#!/bin/bash
# Resume simulation: same transcript_path → same session_key → state file
# survives → statusline picks back up. Per-workspace markers handled.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init resume

gh_fixture_pr feat-resume OPEN develop 77

(cd "$REPO" && git checkout -q -b feat-resume)

# Session A: hook writes row
hook_input_bash "gh pr create -H feat-resume" "$REPO" "$TX" | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
rows_before=$(cat "$STATE_DIR/$SK")

# "Resume" = re-render with same transcript_path
out=$(render_status)
assert_contains "$out" "feat-resume" "row visible after 'resume'"
assert_equal "$(cat "$STATE_DIR/$SK")" "$rows_before" "state file unchanged across resume"

# Workspace marker survives — modern layout uses a directory of session markers.
ws_dir="$STATE_DIR/_by_workspace/$(md5 "$REPO")"
[ -d "$ws_dir" ] || _fail "ws_dir missing or not a directory: $ws_dir"
assert_file_exists "$ws_dir/$SK"

# Render from a different cwd → new ws_dir + marker, original untouched
OTHER="$SBX/elsewhere"
mkdir -p "$OTHER"
(cd "$OTHER" && git init -q -b main && git config user.email t@t && git config user.name t && git -c commit.gpgsign=false commit -q --allow-empty -m init)
render_status "$OTHER" > /dev/null
other_dir="$STATE_DIR/_by_workspace/$(md5 "$OTHER")"
[ -d "$other_dir" ] || _fail "other_dir missing or not a directory"
assert_file_exists "$other_dir/$SK"
assert_file_exists "$ws_dir/$SK"  # original still marked

echo "resume + cross-workspace marker ok"

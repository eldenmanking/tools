#!/bin/bash
# post-push-ci.sh edge parsing: `--head=value`, `-Hvalue`, quoted values,
# leading `cd /repo &&` prefix.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init edge

gh_fixture_pr feat-x OPEN develop 100
gh_fixture_pr feat-y OPEN feat-x   101

run_hook() {
  rm -f "$STATE_DIR/$SK"
  hook_input_bash "$1" "${2:-$REPO}" "$TX" | bash "$HOOK"
}

# --- All equals/no-space forms collapse to a feat-x row
for cmd in \
  "gh pr create --head=feat-x" \
  "gh pr create -H=feat-x" \
  "gh pr create -Hfeat-x"
do
  run_hook "$cmd"
  assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo "")" $'\tfeat-x\t' "$cmd"
done

# --- cd /other-repo && gh pr create -H feat-x  →  row uses /other-repo
OTHER="$SBX/other-repo"
mkdir -p "$OTHER"
(cd "$OTHER" && git init -q -b main && git config user.email t@t && git config user.name t && git -c commit.gpgsign=false commit -q --allow-empty -m init)
run_hook "cd $OTHER && gh pr create -H feat-x" "$REPO"
row=$(cat "$STATE_DIR/$SK" 2>/dev/null || echo "")
assert_contains "$row" "$OTHER" "cd-prefix updates repo_root"
assert_not_contains "$row" "$REPO	" "original cwd repo not used"

# --- echo "gh pr create -H foo" → no row (false positive guard)
run_hook 'echo "gh pr create -H foo"'
assert_equal "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo "")" "" "echo of a gh command must not be captured"

# --- gh pr create -H feat-x && gh pr create -H feat-y → both rows
run_hook "gh pr create -H feat-x && gh pr create -H feat-y"
assert_equal "$(wc -l < "$STATE_DIR/$SK")" "2" "batched create captures both heads"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-x\t' "first batched head"
assert_contains "$row" $'\tfeat-y\t' "second batched head"

# --- gh pr create fails (tool_response.success=false) → no row
rm -f "$STATE_DIR/$SK"
hook_input_bash "gh pr create -H feat-x" "$REPO" "$TX" false | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK"

echo "all hook-edge checks ok"

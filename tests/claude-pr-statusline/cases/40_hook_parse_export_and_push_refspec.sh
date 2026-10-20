#!/bin/bash
# Two parser gaps that silently dropped real PRs:
#
# (a) `cd repo && export GH_HOST=… ⏎ gh pr create --head …`
#     The `export` statement merges into the `gh pr create` sub because an
#     unquoted newline is whitespace to shlex (not a separator). `export`
#     landed at sub[0] so the create was missed entirely.
#
# (b) `git push origin <sha>:refs/heads/<branch>` from a worktree checked
#     out on a DIFFERENT branch. The parser ignored push refspecs and fell
#     back to the current branch → tracked the wrong branch (or nothing).

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init parsefix

# --- (a) export + newline before `gh pr create` → still detected.
gh_fixture_pr andywang/test-243515 DRAFT develop 515
cmd=$(printf 'cd %s && export GH_HOST=ghe.foo\ngh pr create --head andywang/test-243515 --base develop --draft' "$REPO")
hook_input_bash "$cmd" "$REPO" "$TX" | bash "$HOOK"
assert_file_exists "$STATE_DIR/$SK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tandywang/test-243515\t' "(a) create found despite export+newline"

# --- (b) refspec push tracks the destination branch, NOT the current branch.
rm -f "$STATE_DIR/$SK"
(cd "$REPO" && git checkout -q -b a-different-current-branch)
gh_fixture_pr cold-build-memory-baseline OPEN develop 3515
hook_input_bash "git push origin 9f3a1c2:refs/heads/cold-build-memory-baseline" "$REPO" "$TX" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tcold-build-memory-baseline\t' "(b) tracks refspec destination branch"
assert_not_contains "$row" "a-different-current-branch"  "(b) does NOT track the checked-out branch"

# --- (b2) explicit `git push origin <branch>` tracks that branch, not current.
rm -f "$STATE_DIR/$SK"
(cd "$REPO" && git checkout -q -b yet-another-current)
gh_fixture_pr pushed-branch OPEN develop 600
hook_input_bash "git push -u origin pushed-branch" "$REPO" "$TX" | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tpushed-branch\t' "(b2) explicit branch arg tracked"

# --- regression: plain `git push` still falls back to the current branch.
rm -f "$STATE_DIR/$SK"
(cd "$REPO" && git checkout -q -b plain-push-current)
gh_fixture_pr plain-push-current OPEN develop 601
hook_input_bash "git push" "$REPO" "$TX" | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK")" $'\tplain-push-current\t' "plain push → current-branch fallback intact"

# --- delete-only push tracks NOTHING (no spurious current-branch fallback).
rm -f "$STATE_DIR/$SK"
(cd "$REPO" && git checkout -q -b branch-with-no-fixture)
hook_input_bash "git push origin :gone" "$REPO" "$TX" | bash "$HOOK"
assert_file_missing "$STATE_DIR/$SK" "delete-only push writes no row"

echo "export prefix + push refspec parsing ok"

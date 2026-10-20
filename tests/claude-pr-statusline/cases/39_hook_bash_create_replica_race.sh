#!/bin/bash
# Bash `gh pr create` read-replica race (the bug that silently dropped a
# real PR).
#
# When Claude creates a PR via `gh pr create` in Bash, the hook used to
# resolve it with `gh pr view <head>` immediately afterward. On a busy repo
# GitHub's read replica lags a few hundred ms, so that view 404s / returns
# empty and the PR was dropped with NO row, NO flag, NO trace — unlike the
# MCP path, which reconstructs from the tool result.
#
# Fix: the Bash path reads the PR URL straight out of `gh pr create` stdout
# (carried in tool_response.stdout) and tracks from it when the view races;
# `gh_pr_view_full` also retries; and a genuine miss is surfaced instead of
# dropped. An empty `gh pr view` here means "no fixture matched" — exactly
# the lagged-replica shape (rc 0, empty stdout).

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init bashrace

# --- 1. View races (empty), but stdout has the URL → track from stdout.
gh_fixture_reset   # no `pr view` rule ⇒ mock returns empty ⇒ simulated lag
err=$(hook_input_bash \
  "gh pr create --draft -B develop -H feat-race -t t -b b" \
  "$REPO" "$TX" "true" "https://example.com/pr/777" \
  | bash "$HOOK" 2>&1 >/dev/null)
assert_file_exists "$STATE_DIR/$SK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'\tfeat-race\t'             "race: head tracked from stdout"
assert_contains "$row" "https://example.com/pr/777" "race: url from stdout"
assert_contains "$row" $'\tdevelop\t'               "race: base from -B flag"
assert_contains "$row" $'\t777'                      "race: number parsed from URL"
assert_file_exists "$CI_DIR/push-pending-$SK"       "race: push-pending flag set"
assert_contains "$err" "/babysit-ci https://example.com/pr/777" "race: babysit nudge fired"

# --- 2. View races AND no stdout URL → genuine miss is SURFACED, not silent.
rm -f "$STATE_DIR/$SK" "$CI_DIR/push-pending-$SK"
err=$(hook_input_bash "gh pr create -H feat-nourl -B develop" \
  "$REPO" "$TX" "true" "" | bash "$HOOK" 2>&1 >/dev/null)
assert_file_missing "$STATE_DIR/$SK"               "miss: no row when nothing resolvable"
assert_contains "$err" "could not auto-track"      "miss: surfaced on stderr (was silent before)"
assert_contains "$err" "feat-nourl"                "miss: names the dropped head"

# --- 3. View succeeds → full metadata wins over stdout (happy path intact).
#        Fixture base=main differs from the command's -B develop and stdout;
#        the row must take the authoritative view value.
rm -f "$STATE_DIR/$SK"
gh_fixture_pr feat-ok OPEN main 55 "https://example.com/pr/55"
hook_input_bash "gh pr create -B develop -H feat-ok" \
  "$REPO" "$TX" "true" "https://example.com/pr/999" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" "https://example.com/pr/55" "happy: url from view, not stdout"
assert_contains "$row" $'\tmain\t'                 "happy: base from view, not -B flag"
assert_not_contains "$row" "pr/999"               "happy: stdout URL ignored when view works"

# --- 4. Plain push to a branch with no PR → stays SILENT (no false miss nudge).
rm -f "$STATE_DIR/$SK"
gh_fixture_reset
(cd "$REPO" && git checkout -q -b feat-lonelypush)
err=$(hook_input_bash "git push" "$REPO" "$TX" | bash "$HOOK" 2>&1 >/dev/null)
assert_file_missing "$STATE_DIR/$SK"          "push: no row for branch without a PR"
assert_not_contains "$err" "could not auto-track" "push: no false miss nudge for plain push"

# --- 5. Batched create (two PRs, two stdout URLs) → both tracked in order.
rm -f "$STATE_DIR/$SK"
gh_fixture_reset
two_urls=$'https://example.com/pr/801\nhttps://example.com/pr/802'
hook_input_bash \
  "gh pr create -B develop -H feat-b1 && gh pr create -B develop -H feat-b2" \
  "$REPO" "$TX" "true" "$two_urls" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" $'feat-b1\thttps://example.com/pr/801\t' "batch: first create → first url"
assert_contains "$row" $'feat-b2\thttps://example.com/pr/802\t' "batch: second create → second url"

echo "bash create replica race ok"

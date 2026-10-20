#!/bin/bash
# Edge cases around the MCP fast-path and its interaction with the main
# (gh pr view) loop on the same state file:
#   1. Two sequential MCP create calls for the SAME head → dedup, one row.
#   2. MCP fast-path then Bash `git push` → main loop's awk dedup must
#      filter the row the MCP path wrote (no duplicates).
#   3. Env-var prefix on a `cd` subcommand still strips correctly.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init crosspath

# --- 1. Two MCP creates for the same head → single row
mcp_input() {
  jq -nc --arg head "$1" --arg cwd "$REPO" --arg tx "$TX" --argjson resp "$2" \
    '{tool_name:"mcp__github__create_pull_request",
      tool_input:{head:$head},
      cwd:$cwd,
      transcript_path:$tx,
      tool_response:($resp + {success:true})}'
}

resp1='{"html_url":"https://example.com/pr/100","number":100,"state":"open","head":{"ref":"feat-dup"},"base":{"ref":"develop"}}'
mcp_input feat-dup "$resp1" | bash "$HOOK"
# Same head, slightly different URL (server replaced) — should overwrite, not duplicate
resp2='{"html_url":"https://example.com/pr/100","number":100,"state":"draft","head":{"ref":"feat-dup"},"base":{"ref":"main"}}'
mcp_input feat-dup "$resp2" | bash "$HOOK"
assert_equal "$(wc -l < "$STATE_DIR/$SK")" "1" "two MCP creates same head → one row"
assert_equal "$(awk -F'\t' '$2 == "feat-dup" {print $4}' "$STATE_DIR/$SK")" "main" "second call updated base"

# --- 2. MCP fast-path wrote a row; now a Bash `git push` to the same branch
#       must update it via the main loop's dedup, not duplicate.
gh_fixture_pr feat-dup OPEN main 100
(cd "$REPO" && git checkout -q -b feat-dup)
hook_input_bash "git push" "$REPO" "$TX" | bash "$HOOK"
assert_equal "$(wc -l < "$STATE_DIR/$SK")" "1" "MCP→push same branch → still one row"

# --- 3. Env-var prefix on cd: `KEY=val cd /other && gh pr create -H feat-x`
OTHER="$SBX/other-repo"
mkdir -p "$OTHER"
(cd "$OTHER" && git init -q -b main && git config user.email t@t && git config user.name t && git -c commit.gpgsign=false commit -q --allow-empty -m init)
rm -f "$STATE_DIR/$SK"
gh_fixture_reset
gh_fixture_pr feat-cd OPEN develop 200
hook_input_bash "FOO=bar cd $OTHER && gh pr create -H feat-cd" "$REPO" "$TX" | bash "$HOOK"
row=$(cat "$STATE_DIR/$SK")
assert_contains "$row" "$OTHER"      "env-var-prefixed cd captured override"
assert_contains "$row" $'\tfeat-cd\t' "gh create after env-var+cd captured"

echo "mcp dedup + crosspath ok"

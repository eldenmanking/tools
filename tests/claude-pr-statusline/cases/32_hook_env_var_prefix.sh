#!/bin/bash
# Hook parser must handle leading inline env-var assignments:
#   GH_HOST=github.example.com gh pr create -H feat
#   A=1 B=2 git push
# shlex tokenizes the assignment as a separate token; without an explicit
# strip the dispatch sees `sub[0] == 'GH_HOST=...'` and misses the command.

set -e
source "$TEST_ROOT/lib/setup.sh"
test_init envvar

gh_fixture_pr feat-x OPEN develop 100

run_hook() {
  rm -f "$STATE_DIR/$SK"
  hook_input_bash "$1" "${2:-$REPO}" "$TX" | bash "$HOOK"
}

# --- Single env var before gh pr create
run_hook "GH_HOST=github.example.com gh pr create -H feat-x"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "single env var prefix"

# --- Two env vars
run_hook "A=1 B=2 gh pr create -H feat-x"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "multiple env var prefix"

# --- Lowercase env vars (bash allows them too)
run_hook "foo_bar=baz gh pr create -H feat-x"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "lowercase env var prefix"

# --- Env var before gh api
run_hook "GH_HOST=x gh api -X POST /repos/o/r/pulls -f head=feat-x"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "env var before gh api"

# --- Env var before git push
rm -f "$STATE_DIR/$SK"
(cd "$REPO" && git checkout -q -b feat-x)
hook_input_bash "GIT_CONFIG_GLOBAL=/dev/null git push" "$REPO" "$TX" | bash "$HOOK"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "env var before git push"

# --- After cd, env var still strips correctly
OTHER="$SBX/other-repo"
mkdir -p "$OTHER"
(cd "$OTHER" && git init -q -b main && git config user.email t@t && git config user.name t && git -c commit.gpgsign=false commit -q --allow-empty -m init)
run_hook "cd $OTHER && GH_HOST=x gh pr create -H feat-x" "$REPO"
row=$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)
assert_contains "$row" "$OTHER" "cd then env-var-prefixed gh pr create"

# --- Not an env var: --flag=value should NOT be stripped
# (--head=feat-x is a flag, parser handles it via extract_gh_create)
run_hook "gh pr create --head=feat-x"
assert_contains "$(cat "$STATE_DIR/$SK" 2>/dev/null || echo)" $'\tfeat-x\t' "--head= flag still parsed"

echo "env var prefix ok"

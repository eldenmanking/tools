# Sandbox helpers for PR-statusline tests.
#
# Each test sources this and calls mk_sandbox to isolate HOME, prepend the
# mocks dir to PATH, and produce a clean working area. Cleanup runs from
# an EXIT trap. KEEP_SANDBOX=1 (or test failure) preserves the dir.

TEST_ROOT=${TEST_ROOT:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}
SCRIPTS_ROOT=${SCRIPTS_ROOT:-"$(cd "$TEST_ROOT/../../claude/scripts" && pwd)"}

mk_sandbox() {
  SBX=$(mktemp -d -t prsl-test.XXXXXX)
  export HOME="$SBX/home"
  mkdir -p "$HOME"

  export GH_MOCK_FIXTURE="$SBX/gh-fixture.json"
  export GH_MOCK_LOG="$SBX/gh-calls.log"
  export GIT_PUSH_LOG="$SBX/git-push.log"
  : > "$GH_MOCK_LOG"
  : > "$GIT_PUSH_LOG"
  echo '{}' > "$GH_MOCK_FIXTURE"

  export PATH="$TEST_ROOT/mocks:$PATH"

  # Most tests want a real git repo to act as their cwd.
  export REPO="$SBX/repo"
  mkdir -p "$REPO"
  (
    cd "$REPO"
    git init -q -b main
    git config user.email t@t
    git config user.name t
    git -c commit.gpgsign=false commit -q --allow-empty -m init
  )

  trap '_sandbox_cleanup' EXIT
}

_sandbox_cleanup() {
  if [ "${KEEP_SANDBOX:-0}" = "1" ] || [ "${TEST_FAILED:-0}" = "1" ]; then
    echo "  sandbox kept at: $SBX" >&2
  else
    rm -rf "$SBX"
  fi
}

arm_gh_fixture() {
  # Usage: arm_gh_fixture <fixture-file-relative-to-tests-dir>
  # or: arm_gh_fixture --inline '<json>'
  if [ "$1" = "--inline" ]; then
    printf '%s' "$2" > "$GH_MOCK_FIXTURE"
  else
    cp "$TEST_ROOT/$1" "$GH_MOCK_FIXTURE"
  fi
}

strip_ansi() {
  sed 's/\x1b\[[0-9;]*m//g'
}

md5() {
  printf '%s' "$1" | md5sum | cut -d' ' -f1
}

mk_session() {
  local tx="$SBX/transcript-$1.jsonl"
  : > "$tx"
  printf '%s' "$tx"
}

session_key_of() {
  md5 "$1"
}

statusline_input() {
  local cwd="$1" transcript="$2" pct="${3:-42}"
  cat <<EOF
{"workspace":{"current_dir":"$cwd"},"context_window":{"used_percentage":$pct},"transcript_path":"$transcript"}
EOF
}

hook_input_bash() {
  local cmd="$1" cwd="$2" transcript="$3" success="${4:-true}" stdout="${5:-}"
  jq -nc \
    --arg cmd "$cmd" \
    --arg cwd "$cwd" \
    --arg tx "$transcript" \
    --argjson success "$success" \
    --arg stdout "$stdout" \
    '{tool_name:"Bash",tool_input:{command:$cmd},cwd:$cwd,transcript_path:$tx,tool_response:{success:$success,stdout:$stdout}}'
}

hook_input_mcp_create() {
  local head="$1" cwd="$2" transcript="$3"
  jq -nc \
    --arg head "$head" \
    --arg cwd "$cwd" \
    --arg tx "$transcript" \
    '{tool_name:"mcp__github__create_pull_request",tool_input:{head:$head},cwd:$cwd,transcript_path:$tx,tool_response:{success:true}}'
}

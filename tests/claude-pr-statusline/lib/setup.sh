# One-stop test prologue. Sources sandbox.sh + assert.sh + builders.sh
# and exposes `test_init <session_label>` which:
#   - calls mk_sandbox (fresh HOME, repo, mocks on PATH)
#   - exports script paths: HOOK, SL, HELPER, STOP, REFRESH, DISCOVER, CLEANUP
#   - exports state dirs: STATE_DIR, CI_DIR, CACHE_DIR, LOG_DIR
#   - sets TX (transcript path) and SK (session key) for the named session

TEST_ROOT=${TEST_ROOT:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}

# shellcheck source=sandbox.sh
source "$TEST_ROOT/lib/sandbox.sh"
# shellcheck source=assert.sh
source "$TEST_ROOT/lib/assert.sh"
# shellcheck source=builders.sh
source "$TEST_ROOT/lib/builders.sh"

test_init() {
  mk_sandbox
  STATE_DIR="$HOME/.local/state/claude/pr-state"
  CI_DIR="$HOME/.local/state/claude/ci-state"
  CACHE_DIR="$HOME/.local/state/claude/pr-cache"
  LOG_DIR="$HOME/.local/state/claude/pr-log"
  mkdir -p "$STATE_DIR/_by_workspace" "$CI_DIR" "$CACHE_DIR" "$LOG_DIR"
  HOOK="$SCRIPTS_ROOT/post-push-ci.sh"
  SL="$SCRIPTS_ROOT/statusline.sh"
  HELPER="$SCRIPTS_ROOT/pr-state.sh"
  STOP="$SCRIPTS_ROOT/stop-ci-check.sh"
  REFRESH="$SCRIPTS_ROOT/refresh-pr-state-core.sh"
  DISCOVER="$SCRIPTS_ROOT/discover-pr-state-core.sh"
  CLEANUP="$SCRIPTS_ROOT/cleanup-pr-state-core.sh"
  if [ -n "${1:-}" ]; then
    TX=$(mk_session "$1")
    SK=$(session_key_of "$TX")
  fi
}

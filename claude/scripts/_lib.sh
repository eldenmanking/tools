# Shared helpers for the PR-statusline scripts.
# Sourced by:
#   post-push-ci.sh, statusline.sh, pr-state.sh, stop-ci-check.sh,
#   refresh-pr-state-core.sh, discover-pr-state-core.sh, cleanup-pr-state-core.sh
#
# All functions are pure (no global writes besides the documented gh_pr_view_full
# output globals). Constants are exported so callers see them after `source`.

STATE_DIR="$HOME/.local/state/claude/pr-state"
CI_DIR="$HOME/.local/state/claude/ci-state"
CACHE_DIR="$HOME/.local/state/claude/pr-cache"
WORKSPACE_DIR="$STATE_DIR/_by_workspace"

# Debug logger. Set CLAUDE_PRSL_DEBUG=1 to enable, or =<file> to redirect
# to a specific path. Default sink is $STATE_DIR/.debug.log so statusline
# rendering isn't disrupted. Cheap no-op when unset.
dbg() {
  [ -z "${CLAUDE_PRSL_DEBUG:-}" ] && return 0
  local sink="${CLAUDE_PRSL_DEBUG}"
  case "$sink" in
    1|true|yes|on) sink="$STATE_DIR/.debug.log" ;;
  esac
  mkdir -p "$(dirname "$sink")" 2>/dev/null
  printf '%(%Y-%m-%dT%H:%M:%S%z)T %s\n' -1 "$*" >> "$sink" 2>/dev/null || true
}

# Ensure every directory the pipeline writes to exists. Cheap and idempotent.
state_ensure_dirs() {
  mkdir -p "$STATE_DIR" "$WORKSPACE_DIR" "$CI_DIR" "$CACHE_DIR" 2>/dev/null || true
}

# Is this PR's state one we keep tracking? (DRY across hook/refresh/cleanup.)
pr_is_alive() {
  case "$1" in OPEN|DRAFT) return 0 ;; *) return 1 ;; esac
}

# Prune dangling workspace pointers + markers.
#
# Modern markers in `_by_workspace/<ws>/<session_key>` are kept while their
# state file exists, otherwise pruned after $grace seconds (so a brand-new
# session that hasn't yet written its state file isn't yanked on the very
# next tick). Pass grace=0 to prune immediately.
#
# Legacy single-file pointers in `_by_workspace/<ws>` have no grace —
# they always pointed at a real state file at write time.
prune_workspace_pointers() {
  local grace="${1:-0}" now mtime
  now=$(date +%s)
  shopt -s nullglob
  local entry marker mname sk
  for entry in "$WORKSPACE_DIR"/*; do
    if [ -d "$entry" ]; then
      for marker in "$entry"/*; do
        [ -f "$marker" ] || continue
        mname=$(basename "$marker")
        if ! guard_basename "$mname"; then rm -f "$marker"; continue; fi
        [ -f "$STATE_DIR/$mname" ] && continue
        if [ "$grace" -gt 0 ]; then
          mtime=$(stat -c %Y "$marker" 2>/dev/null || stat -f %m "$marker" 2>/dev/null || echo "$now")
          [ $((now - mtime)) -gt "$grace" ] && rm -f "$marker"
        else
          rm -f "$marker"
        fi
      done
      rmdir "$entry" 2>/dev/null || true
    elif [ -f "$entry" ]; then
      sk=$(cat "$entry" 2>/dev/null || true)
      if guard_basename "$sk" && [ -f "$STATE_DIR/$sk" ]; then continue; fi
      rm -f "$entry"
    fi
  done
  shopt -u nullglob
}

# md5 of a string. Used for session_key (transcript_path) and workspace
# bucketing (md5(PWD)).
md5() {
  printf '%s' "$1" | md5sum | cut -d' ' -f1
}

# Reject session keys / workspace IDs / state-file basenames that could
# escape the state-dir via path traversal or contain slashes. Empty string
# also rejected. Caller passes a *basename*, not a full path.
guard_basename() {
  case "$1" in
    */*|*..*|"") return 1 ;;
    *) return 0 ;;
  esac
}

# Run a command (typically `gh ...`), capture stdout into $REPLY, return the
# command's exit code. Safe under `set -e` IFF called inside an `if` test
# (bash suppresses set -e for `if` conditions, including through function
# calls).
#
# Usage:
#   if gh_view_rc gh pr view "$url" --json state; then
#     # success — $REPLY holds stdout
#   else
#     # gh failed — $REPLY may be empty; caller handles
#   fi
gh_view_rc() {
  REPLY=$("$@" 2>/dev/null)
}

# Query a PR and populate globals PR_URL, PR_HEAD, PR_BASE, PR_NUMBER,
# PR_STATE. Strips `-cached` from PR_BASE. Falls back PR_HEAD to the
# input arg if the response is missing headRefName. Returns gh's exit
# code so the caller can distinguish "PR not found / closed" (0 with
# specific state) from "gh failed / network down" (non-zero).
#
# Usage:
#   gh_pr_view_full <url_or_branch> [repo_root] [attempts]
#     repo_root defaults to PWD; we `cd` into it for branch-name lookups.
#     attempts  total tries (default 1). >1 retries with 0.5s/1s/2s backoff
#               when the view fails OR returns empty — both are symptoms of
#               read-replica lag right after a PR is created. Callers that
#               KNOW the PR exists (just ran `gh pr create`) pass 3; plain
#               pushes pass 1 so a branch with genuinely no PR isn't slowed.
gh_pr_view_full() {
  local ref="$1" repo="${2:-.}" attempts="${3:-1}"
  [ "$attempts" -lt 1 ] 2>/dev/null && attempts=1
  PR_URL= PR_HEAD= PR_BASE= PR_NUMBER= PR_STATE=
  local json rc attempt=1
  while :; do
    if json=$(cd "$repo" 2>/dev/null && gh pr view "$ref" --json url,baseRefName,headRefName,number,state 2>/dev/null); then
      rc=0
    else
      rc=$?
    fi
    # Done as soon as we get a non-empty payload.
    { [ "$rc" -eq 0 ] && [ -n "$json" ]; } && break
    # Out of attempts → return whatever we have (caller distinguishes
    # non-zero rc = transport failure from rc 0 + empty = PR not visible).
    [ "$attempt" -ge "$attempts" ] && { [ "$rc" -ne 0 ] && return "$rc"; return 0; }
    case "$attempt" in 1) sleep 0.5 ;; 2) sleep 1 ;; *) sleep 2 ;; esac
    attempt=$((attempt + 1))
  done
  IFS=$'\t' read -r PR_URL PR_BASE PR_HEAD PR_NUMBER PR_STATE < <(
    printf '%s' "$json" | jq -r '[.url, .baseRefName, .headRefName, .number, .state] | map(. // "") | @tsv'
  )
  PR_BASE="${PR_BASE%-cached}"
  [ -z "$PR_HEAD" ] && PR_HEAD="$ref"
  return 0
}

# Emit one TSV row (5 columns — updated_at dropped).
emit_row() {
  printf '%s\t%s\t%s\t%s\t%s\n' "$@"
}

# Upsert a (repo, head) row into a session state file: filter out any
# pre-existing row with the same key, then append the new one. Also
# refreshes the push-pending flag and the per-(repo,branch) cache so a
# concurrent Stop hook / statusline render sees the latest.
#
# Used by both the MCP fast-path and the main `gh pr view` loop in the
# hook so the upsert ceremony stays in one place.
#
# Usage:
#   upsert_pr_state <session_key> <repo_root> <head> <url> <base> <number>
upsert_pr_state() {
  local sk="$1" repo_root="$2" head="$3" url="$4" base="$5" num="$6"
  local state_file="$STATE_DIR/$sk"
  local new_row
  new_row=$(emit_row "$repo_root" "$head" "$url" "$base" "$num")
  if [ -f "$state_file" ]; then
    local existing
    existing=$(awk -F'\t' -v r="$repo_root" -v b="$head" '$1==r && $2==b {next} {print}' "$state_file")
    if [ -n "$existing" ]; then
      { printf '%s\n' "$existing"; printf '%s\n' "$new_row"; } | atomic_write "$state_file"
    else
      printf '%s\n' "$new_row" | atomic_write "$state_file"
    fi
  else
    printf '%s\n' "$new_row" > "$state_file"
  fi
  printf '%s\n' "$url" | atomic_write "$CI_DIR/push-pending-$sk" 2>/dev/null \
    || printf '%s\n' "$url" > "$CI_DIR/push-pending-$sk"
  printf '%s\n' "$url" > "$(pr_cache_path "$repo_root" "$head")"
}

# Path of the per-(repo, branch) PR-URL cache file. Branch names often
# contain `/` (`andywang/feat`) — without sanitization the filename would
# be interpreted as a subdirectory path, breaking writes ("No such file or
# directory") and missing on lookups. Replace `/` with `_`.
pr_cache_path() {
  local repo="$1" branch="$2"
  local safe="${branch//\//_}"
  printf '%s/%s_%s' "$CACHE_DIR" "$(md5 "$repo")" "$safe"
}

# Atomic same-filesystem write under $STATE_DIR.
# Usage:  atomic_write <target>   # reads stdin
atomic_write() {
  local target="$1"
  local tmp
  tmp=$(mktemp "$STATE_DIR/.tmp.XXXXXX")
  cat > "$tmp"
  mv "$tmp" "$target"
}

# Reject any path that would escape $STATE_DIR via `..` traversal or sits
# outside it. Returns 0 if safe, 1 otherwise (with stderr explanation).
guard_under_state_dir() {
  case "$1" in
    *..*) echo "guard: refusing path containing '..': $1" >&2; return 1 ;;
  esac
  case "$1" in
    "$STATE_DIR"/*) return 0 ;;
    *) echo "guard: refusing path outside $STATE_DIR: $1" >&2; return 1 ;;
  esac
}

# Mutation primitives previously dispatched via pr-state.sh subcommands.
# Cores source `_lib.sh` and call these directly — no per-mutation `bash
# pr-state.sh ...` fork.

# Replace target with stdin contents atomically.
write_rows() {
  local target="$1"
  guard_under_state_dir "$target" || return 1
  atomic_write "$target"
}

# Drop a session state file.
drop_state() {
  local target="$1"
  guard_under_state_dir "$target" || return 1
  rm -f "$target"
}

# Clear the push-pending flag for a session_key.
clear_flag() {
  local key="$1"
  guard_basename "$key" || { echo "clear_flag: invalid key: $key" >&2; return 1; }
  rm -f "$CI_DIR/push-pending-$key"
}

#!/bin/bash
# Thin CLI shim for the PR-state pipeline. Slash commands call this via
# their allowlisted `Bash(bash ~/.claude/scripts/pr-state.sh:*)` so they
# don't have to source bash libraries to read state-file paths.
#
# Internal scripts (the *-core.sh family) source `_lib.sh` directly and
# call its functions (atomic_write, guard_under_state_dir, write_rows,
# drop_state, clear_flag, prune_workspace_pointers) without forking
# through this dispatcher.
#
# Usage:
#   pr-state.sh state-file           Print THIS workspace's session state
#                                    file path (may not yet exist).
#   pr-state.sh state-dir            Print the state directory.
#   pr-state.sh ci-dir               Print the ci-state directory.
#   pr-state.sh write-rows <target>  Atomic rewrite from stdin.
#   pr-state.sh clear-flag <key>     Remove push-pending-<key>.
#   pr-state.sh drop-state <target>  Remove a session state file.
#   pr-state.sh prune-pointers       Drop dangling workspace pointers.

set -e

. "$(dirname "$0")/_lib.sh"
state_ensure_dirs

case "$1" in
  state-dir) printf '%s\n' "$STATE_DIR" ;;
  ci-dir)    printf '%s\n' "$CI_DIR" ;;
  state-file)
    target="$WORKSPACE_DIR/$(md5 "$PWD")"
    if [ -d "$target" ]; then
      # Modern marker dir — ls -t picks the most recent renderer.
      newest=$(ls -t "$target" 2>/dev/null | head -1)
      if guard_basename "$newest"; then
        printf '%s\n' "$STATE_DIR/$newest"
      fi
    elif [ -f "$target" ]; then
      # Legacy single-file pointer.
      session=$(cat "$target")
      if guard_basename "$session"; then
        printf '%s\n' "$STATE_DIR/$session"
      fi
    fi
    ;;
  write-rows)
    [ -z "${2:-}" ] && { echo "pr-state.sh write-rows: missing <target>" >&2; exit 1; }
    write_rows "$2"
    ;;
  clear-flag)
    [ -z "${2:-}" ] && { echo "pr-state.sh clear-flag: missing <session_key>" >&2; exit 1; }
    clear_flag "$2"
    ;;
  drop-state)
    [ -z "${2:-}" ] && { echo "pr-state.sh drop-state: missing <target>" >&2; exit 1; }
    drop_state "$2"
    ;;
  prune-pointers)
    prune_workspace_pointers 0
    ;;
  *)
    echo "usage: $0 {state-dir|ci-dir|state-file|write-rows <target>|clear-flag <key>|drop-state <target>|prune-pointers}" >&2
    exit 1
    ;;
esac

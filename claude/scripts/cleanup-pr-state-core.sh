#!/bin/bash
# Core mutation for /cleanup-pr-state. Walks every per-session state file,
# drops MERGED/CLOSED/unreachable rows, deletes session files that end up
# empty, then prunes dangling _by_workspace pointers.
#
# Preserves rows on `gh` transport/auth failure — a network blip can't
# wipe cross-session state.

set -e

. "$(dirname "$0")/_lib.sh"

scanned=0
dropped_merged=0
dropped_closed=0
dropped_unreachable=0
files_deleted=0
dropped_urls=""

shopt -s nullglob
for f in "$STATE_DIR"/*; do
  [ -f "$f" ] || continue
  # Skip the workspace-pointer subdir AND any in-flight atomic-write tempfile.
  case "$(basename "$f")" in _*|.tmp.*) continue ;; esac
  scanned=$((scanned + 1))

  new_rows=()
  while IFS=$'\t' read -r r br_ pr_ base_ num_; do
    [ -z "$r" ] && continue
    if gh_view_rc gh pr view "$pr_" --json state; then
      state_=$(printf '%s' "$REPLY" | jq -r '.state // empty')
      if pr_is_alive "$state_"; then
        new_rows+=("$r"$'\t'"$br_"$'\t'"$pr_"$'\t'"$base_"$'\t'"$num_")
      else
        case "$state_" in
          MERGED) dropped_merged=$((dropped_merged + 1));      dropped_urls="$dropped_urls $pr_(MERGED)" ;;
          CLOSED) dropped_closed=$((dropped_closed + 1));      dropped_urls="$dropped_urls $pr_(CLOSED)" ;;
          *)      dropped_unreachable=$((dropped_unreachable + 1)); dropped_urls="$dropped_urls $pr_(unreachable)" ;;
        esac
      fi
    else
      # Transport / auth failure → preserve.
      dbg "cleanup: gh fail, preserving $pr_"
      new_rows+=("$r"$'\t'"$br_"$'\t'"$pr_"$'\t'"$base_"$'\t'"$num_")
    fi
  done < "$f"

  if [ "${#new_rows[@]}" -eq 0 ]; then
    drop_state "$f"
    files_deleted=$((files_deleted + 1))
  else
    printf '%s\n' "${new_rows[@]}" | write_rows "$f"
  fi
done
shopt -u nullglob

prune_workspace_pointers 0

echo "cleanup: scanned=$scanned merged=$dropped_merged closed=$dropped_closed unreachable=$dropped_unreachable files_deleted=$files_deleted"
if [ -n "$dropped_urls" ]; then
  echo "  dropped:"
  for entry in $dropped_urls; do echo "    $entry"; done
fi

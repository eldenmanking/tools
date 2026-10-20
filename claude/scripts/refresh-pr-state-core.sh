#!/bin/bash
# Core mutation for /refresh-pr-state.
#
# Usage:  refresh-pr-state-core.sh <state_file_path>
# Stdin:  <pr_url>\t<repo_root> per line (PRs Claude observed but the
#         hook may have missed).
#
# Effect:
#   1. Re-queries every existing row by pr_url. Drops MERGED/CLOSED.
#      Refreshes base_branch (strips -cached). PRESERVES rows when gh
#      exits non-zero (transport / auth failure) — a network blip won't
#      wipe state.
#   2. Adds stdin seeds if OPEN/DRAFT and not duplicate.
#   3. Atomic rewrite via pr-state.sh write-rows.
#   4. Clears push-pending flag.

set -e

. "$(dirname "$0")/_lib.sh"

STATE_FILE="${1:-}"
[ -z "$STATE_FILE" ] && { echo "refresh-pr-state-core: missing state file path" >&2; exit 1; }
guard_under_state_dir "$STATE_FILE" || exit 1

existing=""
[ -s "$STATE_FILE" ] && existing=$(cat "$STATE_FILE")
stdin_input=$(cat)

kept=0
preserved_count=0
dropped_urls=""
added_urls=""
tracked_urls=""
new_rows=()

# --- Phase 1: re-query each existing row.
while IFS=$'\t' read -r r br_ pr_ base_ num_; do
  [ -z "$r" ] && continue
  if gh_pr_view_full "$pr_"; then
    if pr_is_alive "$PR_STATE"; then
      [ -z "$PR_HEAD" ]   && PR_HEAD="$br_"
      [ -z "$PR_NUMBER" ] && PR_NUMBER="$num_"
      new_rows+=("$r"$'\t'"$PR_HEAD"$'\t'"$pr_"$'\t'"$PR_BASE"$'\t'"$PR_NUMBER")
      tracked_urls="$tracked_urls $pr_"
      kept=$((kept + 1))
    elif [ -z "$PR_STATE" ]; then
      # gh exit 0 with empty stdout — treat as gone.
      dropped_urls="$dropped_urls $pr_(unreachable)"
    else
      dropped_urls="$dropped_urls $pr_($PR_STATE)"
    fi
  else
    # Transport / auth failure — keep the row as-is.
    dbg "refresh: gh fail, preserving $pr_"
    new_rows+=("$r"$'\t'"$br_"$'\t'"$pr_"$'\t'"$base_"$'\t'"$num_")
    tracked_urls="$tracked_urls $pr_"
    preserved_count=$((preserved_count + 1))
    kept=$((kept + 1))
  fi
done <<< "$existing"

# --- Phase 2: add stdin seeds not already tracked.
while IFS=$'\t' read -r in_url in_repo; do
  [ -z "$in_url" ] || [ -z "$in_repo" ] && continue
  case " $tracked_urls " in *" $in_url "*) continue ;; esac
  if ! gh_pr_view_full "$in_url"; then continue; fi
  pr_is_alive "$PR_STATE" || continue
  [ -z "$PR_HEAD" ] && continue
  new_rows+=("$in_repo"$'\t'"$PR_HEAD"$'\t'"$in_url"$'\t'"$PR_BASE"$'\t'"$PR_NUMBER")
  tracked_urls="$tracked_urls $in_url"
  added_urls="$added_urls $in_url"
done <<< "$stdin_input"

# --- Write back and clear flag.
session_key=$(basename "$STATE_FILE")
if [ "${#new_rows[@]}" -gt 0 ]; then
  printf '%s\n' "${new_rows[@]}" | write_rows "$STATE_FILE"
else
  : | write_rows "$STATE_FILE"
fi
clear_flag "$session_key" 2>/dev/null || true

added_count=$(wc -w <<< "$added_urls")
dropped_count=$(wc -w <<< "$dropped_urls")

echo "refresh: kept=$kept added=$added_count dropped=$dropped_count preserved=$preserved_count"
if [ -n "$dropped_urls" ]; then
  echo "  dropped:"
  for url in $dropped_urls; do echo "    $url"; done
fi
if [ -n "$added_urls" ]; then
  echo "  added:"
  for url in $added_urls; do echo "    $url"; done
fi
if [ "$preserved_count" -gt 0 ]; then echo "  preserved (gh transport failure — row kept as-is)"; fi

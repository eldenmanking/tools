#!/bin/bash
# Core mutation for /discover-pr-state. Walks parent/child chain from each
# seed row using `gh pr list` to find sibling PRs.
#
# Usage:  discover-pr-state-core.sh <state_file_path>
# Stdin:  TSV seed rows (repo, branch, url, base, num).
# Output: short summary.
#
# Walks UP via `gh pr list --head <base>` (single match, else bail), DOWN
# via `gh pr list --base <branch>` (multi OK, recurses). Stops at mainline
# (`main`/`master`/`develop`/`trunk`). Caps 20 new PRs per repo.

set -e

. "$(dirname "$0")/_lib.sh"

STATE_FILE="${1:-}"
[ -z "$STATE_FILE" ] && { echo "discover-pr-state-core: missing state file path" >&2; exit 1; }
guard_under_state_dir "$STATE_FILE" || exit 1
CAP_PER_REPO=20
seeds_stdin=$(cat)

declare -A tracked
declare -A per_repo_count
all_rows=()
discovered_count=0
bails=""

ingest_row() {
  local r="$1" br_="$2" pr_="$3" base_="$4" num_="$5"
  local k="${r}|${br_}"
  [ -n "${tracked[$k]:-}" ] && return 1
  tracked[$k]=1
  all_rows+=("$r"$'\t'"$br_"$'\t'"$pr_"$'\t'"$base_"$'\t'"$num_")
  return 0
}

is_mainline() {
  case "$1" in main|master|develop|trunk) return 0 ;; *) return 1 ;; esac
}

# Build the seed set = existing rows ∪ stdin. Ingest existing first (so
# any duplicate-seed rows in stdin are deduped against existing on `(repo,
# branch)`), then ingest the new stdin seeds.
seed_set=""
[ -s "$STATE_FILE" ] && seed_set=$(cat "$STATE_FILE")
if [ -n "$seeds_stdin" ]; then
  seed_set="${seed_set}${seed_set:+$'\n'}${seeds_stdin}"
fi
while IFS=$'\t' read -r r br_ pr_ base_ num_; do
  [ -z "$r" ] && continue
  ingest_row "$r" "$br_" "$pr_" "$base_" "$num_" || true
done <<< "$seed_set"

# walk_up: find a single open PR whose head = $cur_base. Recurse up.
walk_up() {
  local repo="$1" cur_base="$2"
  while [ -n "$cur_base" ]; do
    cur_base="${cur_base%-cached}"
    is_mainline "$cur_base" && return
    if [ "${per_repo_count[$repo]:-0}" -ge "$CAP_PER_REPO" ]; then
      bails="$bails cap-up($repo)"; return
    fi
    local json
    # gh failure (auth, rate-limit, 5xx) is distinct from "no PRs found".
    # Record the bail rather than silently returning "added=0".
    if ! json=$(cd "$repo" 2>/dev/null && gh pr list --head "$cur_base" --state open --json url,baseRefName,headRefName,number 2>/dev/null); then
      dbg "discover: gh fail walk_up $cur_base"
      bails="$bails gh-fail(up:$cur_base)"; return
    fi
    [ -z "$json" ] && return
    local count
    count=$(printf '%s' "$json" | jq 'length')
    if [ "$count" != "1" ]; then
      # 0 = empty result (silent return), >1 = ambiguous (record + bail).
      # Explicit `return 0` matters under `set -e` — a trailing `[ != ]`
      # that returns 1 would kill the script via the caller.
      if [ "$count" != "0" ]; then
        bails="$bails up-ambig($cur_base,$count)"
      fi
      return 0
    fi
    local p_url p_base p_head p_num
    IFS=$'\t' read -r p_url p_base p_head p_num < <(
      printf '%s' "$json" | jq -r '.[0] | [.url, .baseRefName, .headRefName, .number] | @tsv'
    )
    p_base="${p_base%-cached}"
    if ingest_row "$repo" "$p_head" "$p_url" "$p_base" "$p_num"; then
      per_repo_count[$repo]=$(( ${per_repo_count[$repo]:-0} + 1 ))
      discovered_count=$((discovered_count + 1))
    fi
    cur_base="$p_base"
  done
}

# walk_down: find open PRs whose base = $cur_branch. Recurse on each.
walk_down() {
  local repo="$1" cur_branch="$2"
  [ -z "$cur_branch" ] && return
  if [ "${per_repo_count[$repo]:-0}" -ge "$CAP_PER_REPO" ]; then
    bails="$bails cap-down($repo)"; return
  fi
  local json
  if ! json=$(cd "$repo" 2>/dev/null && gh pr list --base "$cur_branch" --state open --json url,baseRefName,headRefName,number 2>/dev/null); then
    dbg "discover: gh fail walk_down $cur_branch"
    bails="$bails gh-fail(down:$cur_branch)"; return
  fi
  [ -z "$json" ] && return
  # Stream each PR through @tsv and process inline.
  while IFS=$'\t' read -r c_url c_base c_head c_num; do
    [ -z "$c_url" ] && continue
    c_base="${c_base%-cached}"
    if ingest_row "$repo" "$c_head" "$c_url" "$c_base" "$c_num"; then
      per_repo_count[$repo]=$(( ${per_repo_count[$repo]:-0} + 1 ))
      discovered_count=$((discovered_count + 1))
      walk_down "$repo" "$c_head"
    fi
  done < <(printf '%s' "$json" | jq -r '.[] | [.url, .baseRefName, .headRefName, .number] | @tsv')
}

# Walk from the original seed set (not the growing one — keeps termination simple).
while IFS=$'\t' read -r r br_ pr_ base_ num_; do
  [ -z "$r" ] && continue
  walk_up "$r" "$base_"
  walk_down "$r" "$br_"
done <<< "$seed_set"

if [ "${#all_rows[@]}" -gt 0 ]; then
  printf '%s\n' "${all_rows[@]}" | write_rows "$STATE_FILE"
else
  : | write_rows "$STATE_FILE"
fi

echo "discover: added=$discovered_count"
[ -n "$bails" ] && echo "  bails:$bails"
exit 0

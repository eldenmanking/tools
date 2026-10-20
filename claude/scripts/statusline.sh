#!/bin/bash
# Claude Code statusLine script.
# Renders the per-session list of PRs Claude is tracking with the current
# (repo, branch) highlighted. Falls back to a single-line view when no
# session state exists. Auto-seeds the state file when fallback finds a PR.
#
# Maintains _by_workspace/<md5($PWD)>/<session_key> as a touched marker so
# slash commands can resolve "which session's state file is active here".
#
# Tunables:
#   CLAUDE_STATUSLINE_MAX_ROWS       Max tracked rows before "+N more". (10)
#   CLAUDE_STATUSLINE_FORCE_PRUNE    Force prune on every render. (0)
#   CLAUDE_STATUSLINE_MARKER_GRACE   Marker grace period in seconds. (300)
#   CLAUDE_PR_STATUSLINE_AUTOSEED    Auto-seed state from fallback. (1)

. "$(dirname "$0")/_lib.sh"

input=$(cat)
full_cwd=$(echo "$input" | jq -r '.workspace.current_dir')
used=$(echo "$input" | jq -r '.context_window.used_percentage // empty')
used_int=$([ -n "$used" ] && printf '%.0f' "$used" || echo '')
transcript=$(echo "$input" | jq -r '.transcript_path // .session_id // empty')

cur_repo=$(git -C "$full_cwd" rev-parse --show-toplevel 2>/dev/null)
[ -z "$cur_repo" ] && cur_repo="$full_cwd"
cur_branch=$(git -C "$full_cwd" --no-optional-locks symbolic-ref --short HEAD 2>/dev/null \
  || git -C "$full_cwd" --no-optional-locks rev-parse --short HEAD 2>/dev/null \
  || echo '')

shorten() {
  local p="$1"
  if [ -n "$HOME" ] && [[ "$p" == "$HOME"* ]]; then
    printf '~%s' "${p#$HOME}"
  else
    printf '%s' "$p"
  fi
}

# ANSI palette. Data fields always go through %s so they can't inject escapes.
ESC=$'\033'
blue="${ESC}[34m"; green="${ESC}[32m"; purple="${ESC}[35m"
dim="${ESC}[2m";   bold="${ESC}[1m";   reset="${ESC}[0m"

MAX_ROWS="${CLAUDE_STATUSLINE_MAX_ROWS:-10}"

# --- Workspace marker + opportunistic prune.
session_key=""
state_file=""
if [ -n "$transcript" ]; then
  session_key=$(md5 "$transcript")
  state_file="$STATE_DIR/$session_key"
  if [ -n "$full_cwd" ] && [ -n "$HOME" ]; then
    ws_dir="$WORKSPACE_DIR/$(md5 "$full_cwd")"
    if mkdir -p "$ws_dir" 2>/dev/null; then
      touch "$ws_dir/$session_key" 2>/dev/null || true
    elif [ -f "$ws_dir" ]; then
      # Legacy single-file pointer here: migrate to dir form. Racy across
      # concurrent sessions but acceptable — loser's marker just doesn't
      # get set this tick.
      rm -f "$ws_dir" 2>/dev/null && mkdir -p "$ws_dir" 2>/dev/null \
        && touch "$ws_dir/$session_key" 2>/dev/null || true
    fi

    if [ "${CLAUDE_STATUSLINE_FORCE_PRUNE:-0}" = "1" ] || [ $((RANDOM % 20)) -eq 0 ]; then
      prune_workspace_pointers "${CLAUDE_STATUSLINE_MARKER_GRACE:-300}"
    fi
  fi
fi

# --- pr-cache helpers (branch-aware key; `pr_cache_path` lives in _lib.sh
#     so the hook and statusline agree on sanitization).

pr_cache_lookup() {
  local f; f=$(pr_cache_path "$1" "$2")
  [ -f "$f" ] && cat "$f"
}

# Lazy-fill the cache. A `.checked` sentinel prevents repeat misses for
# (repo, branch) pairs gh confirmed have no PR.
#
# We write the sentinel ONLY after gh succeeds (exit 0). A network/auth
# failure leaves both files absent → next render retries. (Previous bug:
# touching the sentinel before the gh call made offline misses sticky.)
pr_cache_fill() {
  local repo="$1" branch="$2"
  [ -z "$branch" ] && return
  local f; f=$(pr_cache_path "$repo" "$branch")
  local marker="${f}.checked"
  [ -f "$f" ] || [ -f "$marker" ] && return 0
  mkdir -p "$CACHE_DIR"
  local url
  if url=$(cd "$repo" && gh pr view "$branch" --json url -q .url 2>/dev/null); then
    if [ -n "$url" ]; then
      echo "$url" > "$f"
    else
      touch "$marker"
    fi
  fi
}

# --- Tracked-stack rendering.
if [ -n "$state_file" ] && [ -s "$state_file" ]; then
  # Stack-sort: BFS the base-branch parent chain to compute per-row depth,
  # then insertion-sort by (repo asc, depth asc).
  sorted=$(awk -F'\t' '
    { lines[NR]=$0; repo[NR]=$1; br[NR]=$2; base[NR]=$4 }
    END {
      n = NR
      for (i = 1; i <= n; i++) {
        cur = i; d = 0; seen = "|"
        while (1) {
          parent = 0
          for (j = 1; j <= n; j++) {
            if (j != cur && repo[j] == repo[cur] && br[j] == base[cur]) { parent = j; break }
          }
          if (parent == 0) break
          if (index(seen, "|" parent "|") > 0) break
          seen = seen parent "|"
          cur = parent; d++
          if (d > n) break
        }
        depth[i] = d; order[i] = i
      }
      for (i = 2; i <= n; i++) {
        j = i
        while (j > 1) {
          a = order[j-1]; b = order[j]
          if (repo[a] > repo[b]) { order[j-1]=b; order[j]=a; j--; continue }
          if (repo[a] == repo[b] && depth[a] > depth[b]) { order[j-1]=b; order[j]=a; j--; continue }
          break
        }
      }
      for (i = 1; i <= n; i++) print lines[order[i]]
    }
  ' "$state_file")

  # Read sorted rows into parallel arrays; record current row's index.
  rows_repo=(); rows_branch=(); rows_url=()
  cur_idx=-1
  while IFS=$'\t' read -r r br_ pr_ _base _num; do
    [ -z "$r" ] && continue
    rows_repo+=("$r"); rows_branch+=("$br_"); rows_url+=("$pr_")
    if [ "$r" = "$cur_repo" ] && [ "$br_" = "$cur_branch" ]; then
      cur_idx=$(( ${#rows_repo[@]} - 1 ))
    fi
  done <<< "$sorted"
  n=${#rows_repo[@]}

  # Apply vertical cap; swap current row into visible window if needed.
  visible=()
  truncated_count=0
  if [ "$n" -le "$MAX_ROWS" ]; then
    for ((i=0; i<n; i++)); do visible+=("$i"); done
  else
    keep=$((MAX_ROWS - 1))
    for ((i=0; i<keep; i++)); do visible+=("$i"); done
    truncated_count=$((n - keep))
    if [ "$cur_idx" -ge 0 ]; then
      in_visible=0
      for v in "${visible[@]}"; do [ "$v" = "$cur_idx" ] && in_visible=1; done
      [ "$in_visible" -eq 0 ] && [ "$keep" -gt 0 ] && visible[$((keep-1))]=$cur_idx
    fi
  fi

  # Build the output as an array; final printf '%s\n' joins it cleanly.
  lines=()
  shown_current=0
  for vi in "${visible[@]}"; do
    r="${rows_repo[$vi]}"; br_="${rows_branch[$vi]}"; pr_="${rows_url[$vi]}"
    short_r=$(shorten "$r")
    if [ "$r" = "$cur_repo" ] && [ "$br_" = "$cur_branch" ]; then
      shown_current=1
      lines+=("${bold}${blue}▶ ${short_r}${reset}  ${green}${br_}${reset}  ${purple}${pr_}${reset}")
    else
      lines+=("${dim}  ${short_r}  ${br_}  ${pr_}${reset}")
    fi
  done
  [ "$truncated_count" -gt 0 ] && lines+=("${dim}  … +${truncated_count} more${reset}")

  # Current (repo, branch) outside the tracked stack → render below with a blank-line separator.
  if [ "$shown_current" -eq 0 ] && [ -n "$cur_repo" ]; then
    pr_cache_fill "$cur_repo" "$cur_branch"
    cur_pr=$(pr_cache_lookup "$cur_repo" "$cur_branch")
    short_cur=$(shorten "$cur_repo")
    br_label="${cur_branch:-no branch}"
    lines+=("")  # blank separator
    if [ -n "$cur_pr" ]; then
      lines+=("${bold}${blue}▶ ${short_cur}${reset}  ${green}${br_label}${reset}  ${purple}${cur_pr}${reset}")
    else
      lines+=("${bold}${blue}▶ ${short_cur}${reset}  ${green}${br_label}${reset}  ${dim}(no PR)${reset}")
    fi
  fi

  [ -n "$used_int" ] && lines+=("${dim}${used_int}%${reset}")
  printf '%s\n' "${lines[@]}"
else
  # --- Fallback: single-line legacy view, with auto-seed.
  cwd_short=$(basename "$full_cwd")
  pr_cache_fill "$cur_repo" "$cur_branch"
  pr_url=$(pr_cache_lookup "$cur_repo" "$cur_branch")

  # Auto-seed: promote the fallback discovery into a tracked state row so
  # the next render uses the multi-line view.
  if [ "${CLAUDE_PR_STATUSLINE_AUTOSEED:-1}" = "1" ] \
     && [ -n "$state_file" ] && [ -n "$pr_url" ] \
     && [ -n "$cur_branch" ] && [ -n "$cur_repo" ]; then
    if gh_pr_view_full "$cur_branch" "$cur_repo" && pr_is_alive "$PR_STATE"; then
      emit_row "$cur_repo" "$PR_HEAD" "$pr_url" "$PR_BASE" "$PR_NUMBER" >> "$state_file"
    fi
  fi

  git_info=""
  [ -n "$cur_branch" ] && git_info="  ${green}${cur_branch}${reset}"
  pr_info=""
  [ -n "$pr_url" ] && pr_info=" ${purple}${pr_url}${reset}"
  ctx_info=""
  [ -n "$used_int" ] && ctx_info=" ${dim}${used_int}%${reset}"
  printf '%s%s%s%s%s%s' "$blue" "$cwd_short" "$reset" "$git_info" "$pr_info" "$ctx_info"
fi

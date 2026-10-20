#!/bin/bash
# Stop hook. If a push happened this session and the push-pending flag is
# still set, surface a reminder.
#
# Modes:
#   - Default (soft): print a single stderr line and exit 0. Claude can
#     stop. The next /refresh-pr-state run clears the flag.
#   - Strict (CLAUDE_PR_STATUSLINE_STRICT=1): block Stop with exit 2 and
#     spell out the three-step recovery (/babysit-ci, /refresh-pr-state,
#     clear-flag). Restores the original blocking behavior.
#
# Flags older than CLAUDE_PR_STATUSLINE_FLAG_TTL seconds (default 7200 =
# 2h) auto-expire and are deleted silently — protects long-running shells
# from being nagged about stale state across reboots / abandoned tasks.

. "$(dirname "$0")/_lib.sh"

input=$(cat)
transcript=$(echo "$input" | jq -r '.transcript_path // .session_id // empty')
[ -z "$transcript" ] && exit 0
session_key=$(md5 "$transcript")

FLAG_FILE="$CI_DIR/push-pending-${session_key}"
[ ! -f "$FLAG_FILE" ] && exit 0

# Auto-expire stale flags.
ttl="${CLAUDE_PR_STATUSLINE_FLAG_TTL:-7200}"
flag_mtime=$(stat -c %Y "$FLAG_FILE" 2>/dev/null || stat -f %m "$FLAG_FILE" 2>/dev/null || echo 0)
now=$(date +%s)
if [ "$flag_mtime" -gt 0 ] && [ $((now - flag_mtime)) -gt "$ttl" ]; then
  rm -f "$FLAG_FILE"
  exit 0
fi

pr_url=$(head -1 "$FLAG_FILE" 2>/dev/null)

if [ "${CLAUDE_PR_STATUSLINE_STRICT:-0}" = "1" ]; then
  echo "You pushed to a PR but haven't finished post-push follow-up. Before stopping, you MUST:" >&2
  echo "1. Spawn a BACKGROUND agent to run /babysit-ci ${pr_url}" >&2
  echo "2. Run /refresh-pr-state to update the PR stack order in the statusline" >&2
  echo "3. After both, clear the flag: bash ~/.claude/scripts/pr-state.sh clear-flag ${session_key}" >&2
  echo "Only then can you stop." >&2
  exit 2
fi

echo "[pr-statusline] push pending: ${pr_url} — run /babysit-ci to monitor CI, /refresh-pr-state to update tracked state" >&2
exit 0

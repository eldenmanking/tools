#!/bin/bash
# SessionStart hook. Records the session id on the tmux pane running Claude
# (pane option @claude_session_id), so the dotfiles tmux-resurrect post-save
# hook (~/.tmux/resurrect/claude_save.sh) can save the pane as
# `claude --resume <id>` and a restore picks the conversation back up.

[ -n "$TMUX_PANE" ] || exit 0

session_id=$(jq -r '.session_id // empty')
[ -n "$session_id" ] || exit 0

tmux set-option -p -t "$TMUX_PANE" @claude_session_id "$session_id" 2>/dev/null
exit 0

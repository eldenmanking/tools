---
name: cleanup-pr-state
description: Walk every session's PR tracking state and drop entries whose PRs have been merged or closed. Removes session files that end up empty. Use to clear out stale state from long-running sessions or across reboots.
allowed-tools: Bash
---

# Cleanup PR state

Walks the global PR state directory and removes tracked entries whose PRs are no longer open. Unlike `/refresh-pr-state` (current session only), this operates across every session.

## Run

```bash
bash ~/.claude/scripts/cleanup-pr-state-core.sh
```

The core script:

- Walks every file in `~/.local/state/claude/pr-state/` (skipping the `_by_workspace` pointer dir).
- For each row, queries the PR's state. Keeps `OPEN`/`DRAFT`. Drops `MERGED`/`CLOSED`/unreachable.
- Atomically rewrites each session file. Deletes any that end up empty.
- Prunes dangling `_by_workspace` pointers at the end.

## Report

The script prints a one-line summary (`scanned= merged= closed= unreachable= files_deleted=`). Surface that to the user. Keep the report under 4 lines.

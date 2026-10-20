---
name: discover-pr-state
description: Manual escape hatch (rarely needed). Expands the session's PR tracking to the ENTIRE stack — walks baseRefName up to mainline and headRefName down through all descendants from each tracked row via `gh pr list`, so the session ends up tracking sibling PRs it never created or touched. This is NOT the normal flow: a session should track only the PRs it actually worked on, which /refresh-pr-state handles. Use this ONLY when the user explicitly wants to adopt a pre-existing stack they inherited but did not work on this session.
allowed-tools: Bash
---

# Discover PR stack

> **Manual escape hatch — not part of normal flow.** By design this tracks *more than the session is responsible for*: it pulls in the whole ancestor chain (up to mainline) **and** every descendant PR, not just the PRs this session created or pushed. That is usually NOT what you want. Reach for `/refresh-pr-state` first — it tracks only session-worked PRs. Only run this command when the user has explicitly asked to adopt an inherited/pre-existing stack.

Walk the base/head chain from each tracked PR to find related open PRs on the remote and add them to the session's state file. Heavier than `/refresh-pr-state` because it issues `gh pr list` calls.

The deterministic walk lives in `~/.claude/scripts/discover-pr-state-core.sh`. Your job is to pick the right seed rows.

## Step 1: Locate the state file

```bash
STATE_FILE=$(bash ~/.claude/scripts/pr-state.sh state-file)
```

If `$STATE_FILE` is empty the statusline hasn't rendered in this workspace yet — ask the user to wait one render tick.

## Step 2: Pick discovery seeds (in priority order)

The currently-checked-out branch may be unrelated to session focus. **Prioritize conversation context over current branch.**

1. **Conversation context (primary).** Walk back through recent tool calls and identify the PRs THIS session has been working on. Use those as seeds.
2. **Existing state rows (secondary).** The core script automatically uses every row already in the state file as a seed, so you don't need to repeat them on stdin.
3. **Current branch (last resort).** ONLY if (1) is empty AND the state file is empty: if the current branch has an open PR, use it as the sole seed.

If after these steps you have no seeds AND the state file is empty, report "no seeds — nothing to discover from" and stop.

## Step 3: Hand seeds to the core script

```bash
printf '%s\n' \
  "$REPO	feat-x	https://example.com/pr/100	develop	100" \
  ... \
  | bash ~/.claude/scripts/discover-pr-state-core.sh "$STATE_FILE"
```

Each stdin line is a full TSV row: `repo_root\tbranch\tpr_url\tbase_branch\tnumber` (TABs, not spaces, 5 columns). The core script:

- Walks up from each row's `base_branch` via `gh pr list --head <base>`. Single match = parent found; multi-match = bail.
- Walks down from each row's `branch` via `gh pr list --base <branch>`. Multi-match is fine; recurses on each.
- Stops at main-line branches (`main`/`master`/`develop`/`trunk`).
- Strips `-cached` suffix on any newly-added `base_branch`.
- Caps at 20 new PRs per repo.

Pass an empty stdin (`printf '' | bash ...`) to walk from existing rows only.

## Step 4: Report

The core prints a one-line summary (`added=N`) and any bail reasons. Surface them. Keep the report under 6 lines.

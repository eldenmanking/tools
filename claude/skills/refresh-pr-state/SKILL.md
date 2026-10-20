---
name: refresh-pr-state
description: Refresh the per-session PR tracking state used by the statusline. This is the primary way to keep the statusline current. Re-queries each tracked PR's state and base branch, drops closed/merged PRs, adds any PRs you know about from this conversation (current branch, or recent PRs you opened) that aren't yet tracked, and clears the push-pending flag. It deliberately tracks only the PRs this session worked on. For the rare case of deliberately adopting a pre-existing stack created outside this session, /discover-pr-state is a manual escape hatch that walks the whole stack.
allowed-tools: Bash
---

# Refresh PR state

The session tracks PRs in `~/.local/state/claude/pr-state/<session_key>` for the statusline to render. This command re-validates what's tracked and adds session-relevant PRs the hook may have missed (e.g. PRs opened via `cd /repo && gh pr create ...` where the hook's cwd didn't match). It does NOT search the remote for unknown PRs — that is intentional, so a session tracks only the PRs it worked on rather than fanning out to the whole stack. For the rare case of deliberately adopting an inherited stack, `/discover-pr-state` is available as a manual escape hatch.

The deterministic file I/O lives in `~/.claude/scripts/refresh-pr-state-core.sh`. Your job is to gather the right PR set from this conversation and feed it in.

## Step 1: Locate the state file

```bash
STATE_FILE=$(bash ~/.claude/scripts/pr-state.sh state-file)
```

If `$STATE_FILE` is empty the statusline hasn't rendered in this workspace yet — ask the user to wait one render tick and retry, rather than guessing at another session's file.

## Step 2: Identify PRs THIS conversation has been working on

Walk back through recent tool calls and results. Collect the URLs of PRs the session has been operating on — created (`gh pr create`, `mcp__github__create_pull_request`, `gh api .../pulls -X POST`), pushed to, edited descriptions on, monitored CI for, reviewed, etc.

For each such PR, also note the `repo_root` it lives in — typically the `cwd` of the command, or the `cd <path>` immediately preceding the `gh` call.

**Do NOT auto-add the current branch's PR.** The currently-checked-out branch may be unrelated to session focus (e.g. a quick exploration branch). Only include it if it shows up in the conversation as session work. The statusline already renders the current branch as a separate block when it's outside the tracked stack.

## Step 3: Hand the list to the core script

```bash
printf '%s\n' "<pr_url_1>	<repo_root_1>" "<pr_url_2>	<repo_root_2>" ... \
  | bash ~/.claude/scripts/refresh-pr-state-core.sh "$STATE_FILE"
```

Each stdin line is `<pr_url>\t<repo_root>` (a real TAB). The core script:

- Re-queries every existing row by its `pr_url`. Drops MERGED/CLOSED. Refreshes `base_branch` (strips `-cached`).
- Adds each stdin PR if it's OPEN/DRAFT and not already tracked.
- Atomically rewrites the state file and clears the push-pending flag.

Pass an empty stdin (`printf '' | bash ...`) when you only need to re-validate.

## Step 4: Report

The core script prints a one-line summary (`kept= added= dropped=`). Surface that to the user along with any dropped URLs. Keep the report under 5 lines.

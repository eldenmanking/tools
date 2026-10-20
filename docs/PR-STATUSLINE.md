# PR statusline

Multi-line Claude Code statusline that shows every PR a session is tracking, with the current branch highlighted (or shown separately when unrelated to the tracked stack). Built so Claude can curate the list from conversation context, with hooks doing automated capture and a few slash commands for repair/discovery.

This doc is the high-level map for anyone working on the feature. Code lives under `~/.claude/scripts/` (symlinked from `tools/claude/scripts/`) and `~/.claude/skills/` (symlinked from `tools/claude/skills/`).

---

## What you see

```
  ~/git/app  andywang/feature-1  https://github.example.com/acme/app/pull/140
  ~/git/app  andywang/feature-2  https://github.example.com/acme/app/pull/163
▶ ~/git/app  andywang/feature-3  https://github.example.com/acme/app/pull/169
42%
```

- Tracked PRs, grouped by repo, stack-sorted (parent→child via `baseRefName` walk within a repo).
- `▶` marks the current `(repo_root, branch)` when it matches a tracked row.
- If the current `(repo_root, branch)` is unrelated to anything tracked, it renders as a separate block below the stack with a blank-line separator. (The current branch may be a test/exploration branch — don't conflate with the session's actual focus.)
- Trailing line is the context-window percentage.

When no state exists for the session, a legacy single-line view (`<cwd> <branch> <pr-url> <ctx%>`) is used as a fallback.

---

## Architecture

```
                  ┌─────────────────────────────┐
                  │   ~/.local/state/claude/    │
                  │   ├── pr-state/             │
                  │   │   ├── <session_key>     │  TSV: repo, branch,
                  │   │   ├── ...               │       pr_url, base,
                  │   │   └── _by_workspace/    │       num
                  │   │       └── <md5($PWD)>   │  → session_key
                  │   ├── ci-state/             │
                  │   │   └── push-pending-<sk> │  PR URL of last push
                  │   └── pr-cache/             │
                  │       └── <md5(repo_root)>  │  legacy fallback
                  └─────────────────────────────┘
                            ▲       ▲
       writes/reads ────────┘       └──── reads (statusline)
                            │       │
       ┌────────────────────┴───┐ ┌─┴────────────────┐
       │ post-push-ci.sh (hook) │ │  statusline.sh   │
       │ PostToolUse on push/   │ │  Per-render. Also │
       │ PR create. Auto-       │ │  writes the      │
       │ captures session work. │ │  _by_workspace   │
       └────────────────────────┘ │  pointer.        │
                                  └──────────────────┘
                                          ▲
                                          │ reads only
                            ┌─────────────┴─────────────┐
                            │ stop-ci-check.sh (Stop)   │
                            │ Blocks Stop on            │
                            │ push-pending; tells       │
                            │ Claude to babysit-ci +    │
                            │ /refresh-pr-state.        │
                            └───────────────────────────┘
                                          ▲
                                          │ called via Bash tool
                            ┌─────────────┴─────────────┐
                            │  pr-state.sh (helper)     │
                            │  Single allowlisted Bash  │
                            │  command. Slash commands  │
                            │  go through it for all    │
                            │  state mutations.         │
                            └───────────────────────────┘
                                          ▲
                                          │
                  ┌───────────────────────┼──────────────────────┐
                  │                       │                      │
       /refresh-pr-state         /discover-pr-state       /cleanup-pr-state
       (refresh-pr-state-core.sh)(discover-pr-state-core.sh)(cleanup-pr-state-core.sh)
       Reconcile state with      Walk gh pr list from     Walk every session's
       conversation context.     existing seeds (head/    state, drop closed/
       Drop MERGED/CLOSED.       base chain). Heavy.      merged PRs globally.
       Add PRs Claude knows      Each slash command's .md is a thin Claude-facing
       about from this session.  wrapper; the *-core.sh does the deterministic I/O.
```

---

## State files

All under `~/.local/state/claude/` (persistent across `/tmp` wipes and `--resume`).

### `pr-state/<session_key>` — per-session TSV

- `session_key = md5(transcript_path)` (or `md5(session_id)` if no transcript).
- Columns (TAB-separated, 5 columns):
  1. `repo_root` — git toplevel of the repo
  2. `branch` — the PR's `headRefName` (typically equals the local branch name when checked out)
  3. `pr_url`
  4. `base_branch` — `baseRefName` with any `-cached` suffix stripped (Spr/restack stack tools target a `develop-cached` mirror)
  5. `number`
- Dedup key: `(repo_root, branch)`. Pushing the same branch repeatedly updates; switching branches in the same workdir adds new rows.

### `pr-state/_by_workspace/<md5($PWD)>/<session_key>` — workspace markers

Modern layout: `<md5($PWD)>` is a **directory** of zero-byte `touch`ed markers, one per session that has rendered in that workspace. The mtime is what carries the "most recently active here" signal.

- Touched by the statusline on every render.
- `pr-state.sh state-file` resolves the workspace dir via `ls -t` and prints the most-recently-touched session's state file path — even if the state file doesn't exist yet (the caller may be about to write it).
- Two concurrent sessions in the same workspace each leave their own marker — neither overwrites the other.
- Stale markers (no corresponding state file, mtime older than `CLAUDE_STATUSLINE_MARKER_GRACE`, default 300s) get pruned on the next opportunistic render.
- Legacy: `<md5($PWD)>` as a regular file containing a single session_key is still recognized (old sessions). Statusline migrates them to dir form on next render.

### `ci-state/push-pending-<session_key>` — Stop nudge flag

- Contents: a PR URL.
- Written by the post-push hook on any push/create.
- Read by the Stop hook to block Claude from stopping until it spawns `/babysit-ci` and runs `/refresh-pr-state`.
- Cleared via `pr-state.sh clear-flag <session_key>`.

### `pr-cache/<md5(repo_root)>_<branch>` — fallback cache + auto-seed substrate

- Branch-aware: switching branches invalidates the previous lookup.
- Used by the statusline's single-line fallback when no session state exists, and by the auto-seed path that promotes the legacy view into the multi-line state on first render.
- Lazy-populated via `gh pr view <branch> --json url` per `(repo, branch)` on first miss; a `.checked` sentinel prevents repeat misses for branches with no PR.

---

## Components

### `post-push-ci.sh` — PostToolUse hook

Fires on `Bash` and `mcp__github__create_pull_request`. Detects:

- `gh pr create` (Bash): parses `-H/--head`, `-B/--base`, and `--draft` from args. Handles batches (e.g. `gh pr create -H a && gh pr create -H b`).
- `gh api -X POST .../pulls -f head=<branch>`: parses `-f head=` form.
- `mcp__github__create_pull_request`: reads `.tool_input.head`.
- `git push` (Bash): parses the refspec destination — `git push origin <branch>`, `… <src>:refs/heads/<dst>`, force (`+src:dst`) — so a worktree pushing one branch while checked out on another tracks the *pushed* branch, not the checked-out one. Deletes (`:branch`) and tag pushes track nothing. Only a refspec-less `git push` / `git push origin` falls back to the current branch.

A leading `export`/`declare` keyword + its assignments is stripped along with inline `KEY=VALUE` prefixes, so an `export GH_HOST=…` statement that merges into the next command's token stream across a newline doesn't hide the `gh pr create`.

The Bash parser emits 5-field rows (`repo  head  base  kind  draft`, `kind ∈ {create, api, push}`) so the shared tracking loop knows whether a PR is *expected* to exist.

For each captured head, runs `gh pr view <head> --json url,baseRefName,headRefName,number,state` from the repo root, strips `-cached` from `baseRefName`, and writes a row to the session state file. Sets the `push-pending` flag with the last PR URL.

**Read-replica race handling (the `gh pr create` path).** Right after creation, GitHub's read replicas lag a few hundred ms, so a `gh pr view <head>` can 404 / return empty and the PR would be dropped. Three guards (mirroring what the MCP fast-path already did):
1. **stdout fast-path** — the URL `gh pr create` printed is carried in `tool_response.stdout`; if the view races, the row is reconstructed from that URL (number parsed from it, base from the `-B/--base` flag). A created PR is therefore never silently dropped as long as `gh` printed its URL.
2. **bounded retry** — `gh_pr_view_full` retries create-ish lookups 3× (0.5s/1s/2s backoff). Plain pushes view once so a branch with genuinely no PR isn't slowed.
3. **no silent miss** — a create whose URL can't be resolved by either path emits a `could not auto-track …` stderr nudge and a `dbg` line, instead of vanishing.

**Hooks bypass Bash tool permission prompts**, so the hook can write anywhere without user approval.

**Failure modes**:
- If `cd /other/repo && gh pr create ...` is in the cmd, the hook's `cwd` is the calling cwd, not `/other/repo`. The `gh pr view <head>` from the wrong repo returns nothing → it falls back to the stdout URL if present, else surfaces a miss. `/refresh-pr-state`'s conversation-context cross-check is the recovery path.

### `stop-ci-check.sh` — Stop hook

If `push-pending-<session_key>` exists and hasn't expired (`CLAUDE_PR_STATUSLINE_FLAG_TTL`, default 7200s):

- Soft (default): emits a single stderr reminder line and exits 0. Claude can stop. The next `/refresh-pr-state` clears the flag.
- Strict (`CLAUDE_PR_STATUSLINE_STRICT=1`): exits 2 with the full three-step recovery (`/babysit-ci`, `/refresh-pr-state`, `clear-flag`).

Stale flags (older than TTL) are silently deleted, so an abandoned task can't haunt the next session.

### `statusline.sh` — statusLine command

Reads JSON from stdin (Claude Code contract: `workspace.current_dir`, `context_window.used_percentage`, `transcript_path`, falling back to `session_id`). Writes the `_by_workspace/<md5($PWD)>` pointer when transcript, cwd, and `$HOME` are all non-empty.

If session state file exists and non-empty:
- Stack-sorts within each repo via an embedded awk routine (BFS via `base_branch` parent walk; insertion sort by `(repo, depth)`).
- Renders one row per tracked PR; current row gets `▶ ` and bold/blue/green/purple palette, others get dim.
- If current `(repo_root, branch)` isn't in the stack, appends a blank-line separator and a "▶ current  branch  (no PR or url-from-cache)" block beneath.

Otherwise renders single-line legacy view.

ANSI escape sequences are pre-built into shell variables (`blue`, `green`, …) and concatenated into rendered lines as plain strings; data fields go through `%s`, never `%b`, so a branch name containing `\n` can't inject layout.

### `pr-state.sh` — mutation helper

Single-purpose script invoked by slash commands. Subcommands:

| subcommand | purpose |
|---|---|
| `state-dir` | print state directory |
| `ci-dir` | print ci-state directory |
| `state-file` | print THIS workspace's session state file path (may not exist; empty if no pointer) |
| `write-rows <target>` | atomic replace `<target>` from stdin (must live under state-dir) |
| `clear-flag <session_key>` | rm push-pending flag |
| `drop-state <target>` | rm a session state file |
| `prune-pointers` | rm `_by_workspace` pointers whose target session file no longer exists |

Refuses to touch paths outside `state-dir` (`_lib.sh:guard_under_state_dir`). Refuses malformed session keys (containing `/` or `..`).

Allowlisted with `Bash(bash ~/.claude/scripts/pr-state.sh:*)` in `settings.json` so slash commands don't trigger per-mutation Bash permission prompts.

### Slash commands (markdown skill files)

#### `/refresh-pr-state`
Lean default. Reconciles state with conversation context:
1. Locate state file via `pr-state.sh state-file`.
2. Re-query each tracked row, drop MERGED/CLOSED, refresh `base_branch`.
3. Walk conversation context for PRs Claude has worked on; add any missing OPEN/DRAFT ones.
4. Write back via `pr-state.sh write-rows`; clear flag via `pr-state.sh clear-flag`.
5. Report.

**Does NOT auto-add the current branch's PR** — the current branch may be unrelated to session focus. Only adds if the PR is in conversation context.

#### `/discover-pr-state`
Heavy, on-demand **manual escape hatch — not part of normal flow.** A session is meant to track only the PRs it actually worked on (created/pushed/edited); `/refresh-pr-state` is the primary path and nothing in the automatic flow (hook, Stop nudge, auto-seed) invokes discovery. This command deliberately breaks that principle: it expands tracking to the *entire* stack — all ancestors up to mainline plus all descendants — which is more than the session is responsible for. Run it only when the user explicitly asks to adopt an inherited/pre-existing stack.

Walks `gh pr list --head/--base` from seed rows.
1. Locate state file.
2. Seeds in priority order: (a) PRs from conversation context, (b) existing state rows, (c) current branch's PR — last resort only.
3. Walk up via `base_branch` (`gh pr list --head <base> --state open`, expects exactly one match), walk down via `branch` (`gh pr list --base <head> --state open`, multiple OK). Cap 20 new PRs/repo. Stops at main-line branches (`main`/`master`/`develop`/`trunk`).
4. Write back via helper. Doesn't drop or re-query existing rows.

#### `/cleanup-pr-state`
Cross-session cleanup. Walks every file in `pr-state/`, queries each PR, drops MERGED/CLOSED, deletes empty session files, prunes dangling workspace pointers (target session file missing). Malformed pointer contents — slashes, `..`, empty — are scrubbed instead by the statusline's opportunistic per-render prune.

---

## Lifecycles

### Normal push flow
1. Claude runs `gh pr create -H X` (or `git push`).
2. PostToolUse `post-push-ci.sh` fires, looks up the PR, adds/updates a row, sets `push-pending` flag.
3. Stop hook reads flag → Claude spawns `/babysit-ci`, runs `/refresh-pr-state`, then helper clears the flag.
4. Statusline picks up the new row on its next render tick.

### Cross-repo session
- Session worked in `~/git/app` (added rows) and `~/git/scratch` (added rows).
- Both repos appear as separate groups in the statusline, stack-sorted within each.

### `--resume`
- `transcript_path` is preserved → `session_key` is preserved → state file at `~/.local/state/claude/pr-state/<session_key>` survives.
- Statusline re-renders with the same tracked rows.

### Current branch unrelated to tracked stack
- Tracked: app PRs from earlier in the session.
- User `cd`s to `~/git/other` to investigate something on a non-PR branch.
- Statusline shows tracked app stack at top, blank line, then `▶ ~/git/other  feat-x  (no PR)` block below.

---

## Known sharp edges / improvement opportunities

- **Stack walking is per-repo only.** No cross-repo stack relationships even when PRs reference each other.
- **`gh pr view` is sequential in the hook.** For batched creates, the loop runs N synchronous `gh` calls. Parallelizing would speed things up but introduce locking concerns on the state file write path.
- **`_by_workspace` pointer collisions.** Two Claude sessions in the same workspace will fight over the same pointer; last-render wins. Rare but possible.
- **`-cached` suffix is hardcoded.** Other stack-tool conventions (e.g. SPR's `spr/` prefix branches) aren't recognized. Add another normalization rule if needed.
- **Helper guard accepts any path under `state-dir`, including `_by_workspace/<key>`.** A malicious caller could write nonsense pointer data, though attack surface is limited to the local user.

Previously documented edges, now fixed:
- Long PR URLs (kept full intentionally — no truncation).
- Vertical-space cap (`CLAUDE_STATUSLINE_MAX_ROWS`, default 10, current row always visible).
- Hook command parsing (shlex tokenizer handles `--head=`, `-Hvalue`, quoted args, leading `cd /other-repo &&`).
- Hook ignoring tool failures (`tool_response.success == false` short-circuits).
- `pr-cache/` branch invalidation (cache key is now `<md5(repo)>_<branch>`).
- `%b` data injection (all data fields render via `%s`; ANSI is pre-built).
- Dangling pointer accumulation (statusline opportunistically prunes ~once per 20 renders).
- `_by_workspace` pointer collisions: replaced with a marker DIRECTORY, one touched file per session — concurrent sessions no longer overwrite each other.
- `gh` transport outages no longer wipe state: refresh/cleanup distinguish auth/network failure (exit non-zero → preserve row) from "PR not found" (exit 0 → drop).
- Stop hook is soft by default (one-line reminder, exit 0). Strict blocking via `CLAUDE_PR_STATUSLINE_STRICT=1`. Flags auto-expire after `CLAUDE_PR_STATUSLINE_FLAG_TTL` seconds.
- Auto-seed: on first render, a branch with an open PR seeds itself into the state file, promoting the legacy single-line view into the multi-line tracked view next tick. Opt out with `CLAUDE_PR_STATUSLINE_AUTOSEED=0`.
- `pr-state.sh` guard rejects `..` segments anywhere (`$STATE_DIR/../etc/foo` no longer escapes).
- Hook atomic-writes use `mktemp` under `$STATE_DIR` so the rename stays atomic on container filesystems.
- Hook passes the bash command to its python parser via env (`$CMD`) rather than argv, avoiding ARG_MAX surprises.
- **Bash `gh pr create` read-replica race.** The Bash path used to depend solely on a `gh pr view <head>` fired immediately after creation, which races GitHub's lagging read replica → 404 → silent drop (no row, no flag, no trace). Now: track from the `gh pr create` stdout URL when the view races, retry the view 3× for create-ish commands, and surface a `could not auto-track …` nudge + `dbg` line on a genuine miss. The MCP path already had an equivalent guard; the Bash path now matches it.
- **`export` prefix + push refspec parsing.** Two parser gaps: (1) `cd repo && export GH_HOST=… ⏎ gh pr create …` hid the create because the `export` statement merged into its sub across the newline — now stripped like an env prefix; (2) `git push origin <sha>:refs/heads/<branch>` from a worktree on a different branch tracked the wrong branch — the parser now reads the refspec destination instead of always falling back to the current branch.

## Testing

Run the suite:

```bash
bash ~/tools/tests/claude-pr-statusline/run.sh
```

Lives outside `claude/` so the dotfiles installer doesn't symlink it. Each case runs in an isolated `$HOME`, mocks `gh` via a fixture file, and uses real `git` against a throwaway repo. Use `KEEP_SANDBOX=1` to preserve the sandbox on failure; `UPDATE_SNAPSHOTS=1` to refresh expected outputs; or filter by case-name fragment (e.g. `run.sh 03 hook` runs case 03 and any whose name contains "hook").

Helpers / mocks:

- `tests/claude-pr-statusline/lib/sandbox.sh` — sandbox setup, JSON payload builders.
- `tests/claude-pr-statusline/lib/assert.sh` — `assert_equal`, `assert_contains`, `diff_snapshot`.
- `tests/claude-pr-statusline/mocks/{gh,git}` — fixture-driven `gh`, no-op `git push`.

Coverage matrix (26 cases):

| # | Targets | Notes |
|---|---|---|
| 01 | `pr-state.sh` | All subcommands, guard rails |
| 02 | `post-push-ci.sh` | `-H`, `--head`, MCP, fallback, dedup |
| 03 | `post-push-ci.sh` | `--head=`, `-Hvalue`, `cd-prefix`, quoted, false positives, tool failure |
| 04 | `post-push-ci.sh` | TSV shape, `-cached` strip, MERGED skip, branch-aware cache |
| 05 | `post-push-ci.sh` | Non-push commands, missing transcript, empty `gh pr view` |
| 06 | `statusline.sh` | Legacy single-line fallback |
| 07 | `statusline.sh` | Stack sort (depth-first within repo) |
| 08 | `statusline.sh` | Current branch outside tracked stack → separate block |
| 10 | `statusline.sh` | `CLAUDE_STATUSLINE_MAX_ROWS` cap with current row preservation |
| 11 | `statusline.sh` | Detached HEAD renders short SHA |
| 12 | `statusline.sh` | `shorten()` empty-HOME guard |
| 13 | `statusline.sh` | `%s` (not `%b`) for data fields |
| 14 | helper + statusline | Pointer pruning, opportunistic statusline cleanup |
| 15 | `refresh-pr-state-core.sh` | Re-query, drop MERGED, add stdin, `-cached` strip, clear flag |
| 16 | `discover-pr-state-core.sh` | Walk up (single match), walk down (multi), `-cached`, stdin seeds, ambiguous bail |
| 17 | `cleanup-pr-state-core.sh` | All-session sweep, empty file delete, pointer prune |
| 18 | end-to-end | hook → row → statusline → Stop nudge → refresh-clear → silent Stop |
| 19 | resume | Same transcript_path = same session_key = state survives; per-workspace markers |
| 20 | `post-push-ci.sh` | `gh api -X POST .../pulls -f head=X`, PATCH/non-pulls rejected |
| 21 | `post-push-ci.sh` | Unmatched quote in command → graceful exit 0, no row |
| 22 | hook + statusline | `session_id` fallback when `transcript_path` is absent |
| 23 | `statusline.sh` | ANSI-stripped snapshot diff (canonical 2-PR render) |
| 24 | `stop-ci-check.sh` | Soft default, strict opt-in, TTL auto-expiry |
| 25 | `statusline.sh` | Auto-seed: legacy fallback writes state file when current branch has a PR |
| 28 | hook parser extras | `;` separator, `(...)` subshell stripping, `-XPOST`, `--method=POST`, MCP `success=false` short-circuit |
| 29 | statusline cache | `.checked` sentinel NOT stuck after gh network failure |
| 30 | discover-core | Records `gh-fail(up:...)` / `gh-fail(down:...)` bails on transport failure |
| 27 | statusline + helper | Per-session marker dir: concurrent sessions don't collide; legacy pointer migrates |
| 39 | `post-push-ci.sh` | Bash `gh pr create` replica race: stdout fast-path, base from `-B`, miss surfaced (not silent), view-wins-over-stdout, plain-push stays silent, batched creates |
| 40 | `post-push-ci.sh` | `export`+newline before `gh pr create`; push refspec dst (`sha:refs/heads/x`, explicit branch, `-u`) tracked over current branch; plain-push fallback intact; delete/tag pushes track nothing |

## File map

| Path | Role |
|---|---|
| `tools/claude/scripts/post-push-ci.sh` | PostToolUse hook |
| `tools/claude/scripts/stop-ci-check.sh` | Stop hook |
| `tools/claude/scripts/statusline.sh` | statusLine command |
| `tools/claude/scripts/pr-state.sh` | Mutation helper |
| `tools/claude/scripts/refresh-pr-state-core.sh` | Deterministic core for `/refresh-pr-state` |
| `tools/claude/scripts/discover-pr-state-core.sh` | Deterministic core for `/discover-pr-state` |
| `tools/claude/scripts/cleanup-pr-state-core.sh` | Deterministic core for `/cleanup-pr-state` |
| `tools/claude/skills/refresh-pr-state/SKILL.md` | Skill (Claude curates inputs, calls core) |
| `tools/claude/skills/discover-pr-state/SKILL.md` | Skill |
| `tools/claude/skills/cleanup-pr-state/SKILL.md` | Skill |
| `tools/tests/claude-pr-statusline/` | Test harness (lives outside `claude/`) |
| `tools/claude/settings.json` | Hook + statusline + permission config (deep-merged on install) |
| `dotfiles/install.sh` (`--claude`) | Clones this repo, symlinks scripts/skills into `~/.claude/`, merges settings |

#!/bin/bash
# PostToolUse hook: detect git push or PR creation and persist state.
#
# Captures (repo_override, head) pairs from the invoking command using a
# shlex-based parser. Supports `gh pr create -H X`, `--head X`, `--head=X`,
# `-HX`, `cd /other-repo && gh pr create -H X`, batched `&& gh pr create`,
# `gh api -X POST .../pulls -f head=X`, and MCP create_pull_request. Falls
# back to the current branch for plain `git push`. Refuses to track a PR
# when the tool reported failure.
#
# Side effects:
# 1. ci-state/push-pending-<session_key>  — Stop hook nudge flag.
# 2. pr-state/<session_key>               — TSV row per tracked PR.
# 3. pr-cache/<md5(repo)>_<branch>        — fallback PR URL cache.

. "$(dirname "$0")/_lib.sh"

input=$(cat)

# Single jq pass: all needed fields in one fork. `success` is encoded as
# the literal string "false" when present and false, else "true" (`//` would
# treat boolean false as absent).
IFS=$'\t' read -r tool_name tool_success transcript cwd mcp_head <<< "$(
  echo "$input" | jq -r \
    '[.tool_name // "",
      (if .tool_response.success == false then "false" else "true" end),
      .transcript_path // .session_id // "",
      .cwd // "",
      .tool_input.head // ""] | @tsv'
)"

[ "$tool_success" = "false" ] && exit 0
[ -z "$transcript" ] && exit 0
[ -z "$cwd" ] && exit 0
session_key=$(md5 "$transcript")

# Rows emitted by the parser as 5 TAB fields:
#   <repo_override> <head> <base> <kind> <draft>
# Empty head means "use the cwd's current branch" (covers plain `git push`
# and `gh pr create` with no explicit head). kind ∈ {create, api, push}.
pairs=""
create_stdout=""
if [ "$tool_name" = "mcp__github__create_pull_request" ]; then
  [ -z "$mcp_head" ] && exit 0
  # Avoid `gh pr view <head>` against a brand-new PR (GitHub's read replicas
  # lag for hundreds of ms → 404 → empty result → race). Reconstruct PR
  # metadata from BOTH tool_response and tool_input: the MCP server's
  # response often contains only {id, url}, but tool_input always carries
  # head/base (required to create the PR). Number is parsed from the URL
  # (`/pull/<n>`); state defaults to OPEN, or DRAFT if tool_input.draft.
  # Use mapfile (one jq value per line) instead of `IFS=$'\t' read` because
  # bash's whitespace-IFS rule collapses consecutive tabs, eating empty
  # fields. The `number` field is often empty (minimal MCP response shape)
  # which would shift every subsequent column.
  mapfile -t mcp_fields < <(
    echo "$input" | jq -r '
      (.tool_response // {}) as $r |
      (.tool_input // {}) as $i |
      ($r.html_url // $r.url // "") as $url |
        $url,
        (($r.number // "") | tostring),
        (($r.state // (if $i.draft == true then "DRAFT" else "OPEN" end)) | ascii_upcase),
        ($r.head.ref // $r.headRefName // $i.head // ""),
        ($r.base.ref // $r.baseRefName // $i.base // "")'
  )
  mcp_url="${mcp_fields[0]:-}"
  mcp_number="${mcp_fields[1]:-}"
  mcp_state="${mcp_fields[2]:-}"
  mcp_head_resp="${mcp_fields[3]:-}"
  mcp_base="${mcp_fields[4]:-}"
  mcp_base="${mcp_base%-cached}"
  # Parse number from URL when the response omits it (common minimal shape).
  # GitHub uses `/pull/<n>` but tests sometimes use `/pr/<n>` — accept any
  # trailing `/<digits>` segment (optionally followed by `/`).
  if [ -z "$mcp_number" ] && [ -n "$mcp_url" ]; then
    mcp_number=$(printf '%s' "$mcp_url" | sed -nE 's,.*/([0-9]+)/?$,\1,p')
  fi

  if [ -n "$mcp_url" ] && [ -n "$mcp_head_resp" ] && [ -n "$mcp_base" ] \
     && [ -n "$mcp_number" ] && pr_is_alive "$mcp_state"; then
    state_ensure_dirs
    repo_root=$(git -C "$cwd" rev-parse --show-toplevel 2>/dev/null)
    [ -z "$repo_root" ] && repo_root="$cwd"
    upsert_pr_state "$session_key" "$repo_root" "$mcp_head_resp" "$mcp_url" "$mcp_base" "$mcp_number"
    # Babysit-ci nudge on the SAME turn as the push — the Stop-hook
    # reminder only fires at turn boundaries which Claude often skips.
    echo "[pr-statusline] tracked PR $mcp_url — next step: spawn a background agent running /babysit-ci $mcp_url to monitor CI now, rather than waiting until the turn ends." >&2
    exit 0
  fi
  # Still incomplete (no URL at all — server returned nothing useful) →
  # fall back to `gh pr view`. Log so silent regressions surface.
  dbg "mcp fast-path incomplete: url='$mcp_url' head='$mcp_head_resp' base='$mcp_base' num='$mcp_number' state='$mcp_state' — falling back to gh pr view"
  # 5-field row (repo, head, base, kind, draft) so the shared loop treats it
  # as a create: retries the view, and surfaces a miss instead of dropping.
  pairs=$(printf '%s\t%s\t%s\t%s\t%s\n' '' "$mcp_head" "$mcp_base" 'create' '')
elif [ "$tool_name" = "Bash" ]; then
  cmd=$(echo "$input" | jq -r '.tool_input.command // empty')
  [ -z "$cmd" ] && exit 0
  # The PR URL `gh pr create` (or `gh api .../pulls`) printed. The fix's
  # core: track from this when `gh pr view` races the read replica.
  create_stdout=$(echo "$input" | jq -r '.tool_response.stdout // ""')
  pairs=$(CMD="$cmd" python3 <<'PY'
import re, shlex, os, sys

cmd = os.environ.get('CMD', '')
# shlex doesn't isolate single-char operators ';' '|' from neighbouring
# tokens — `a; b` tokenizes as `['a;', 'b']`. Pre-space them so they
# come out as their own tokens. Also strip stray `( ... )` subshell
# wrappers, which would attach to `cd` / `feat` and break detection.
cmd = re.sub(r'(?<![&|])([;|&])(?![|&])', r' \1 ', cmd)  # avoid breaking `&&`/`||`
cmd = re.sub(r'[()]', ' ', cmd)
try:
    tokens = shlex.split(cmd, posix=True, comments=False)
except ValueError:
    sys.exit(0)

SEPS = {'&&', '||', ';', '|'}
subs, cur = [], []
for t in tokens:
    if t in SEPS:
        if cur:
            subs.append(cur); cur = []
    else:
        cur.append(t)
if cur:
    subs.append(cur)

def extract_gh_create(args):
    # Returns (heads, base, draft). base/draft are needed to reconstruct the
    # state row from `gh pr create` stdout when `gh pr view` races the replica.
    heads = []
    base = ''
    draft = False
    i = 0
    while i < len(args):
        a = args[i]
        if a in ('-H', '--head') and i + 1 < len(args):
            heads.append(args[i+1]); i += 2; continue
        if a in ('-B', '--base') and i + 1 < len(args):
            base = args[i+1]; i += 2; continue
        if a.startswith('--head='):
            heads.append(a[len('--head='):])
        elif a.startswith('-H=') and len(a) > 3:
            heads.append(a[3:])
        elif a.startswith('-H') and len(a) > 2 and a != '--head':
            heads.append(a[2:])
        elif a.startswith('--base='):
            base = a[len('--base='):]
        elif a.startswith('-B=') and len(a) > 3:
            base = a[3:]
        elif a.startswith('-B') and len(a) > 2 and a != '--base':
            base = a[2:]
        elif a in ('-d', '--draft'):
            draft = True
        i += 1
    return heads, base, draft

def extract_git_push(args):
    # Pull the destination branch(es) out of `git push` refspecs so the row
    # tracks what was actually pushed, not whatever happens to be checked out
    # (a worktree may push `<sha>:refs/heads/other` while sitting on a
    # different branch — the old "current branch" fallback then mistracked).
    # Returns [] when there's no positional refspec → caller keeps the
    # current-branch fallback (plain `git push`).
    VAL_FLAGS = {'-o', '--push-option', '--repo', '--receive-pack', '--exec'}
    positionals = []
    i = 0
    while i < len(args):
        a = args[i]
        if a in VAL_FLAGS and i + 1 < len(args):
            i += 2; continue
        if a.startswith('-'):
            i += 1; continue
        positionals.append(a)
        i += 1
    # positionals[0] is the remote; the rest are refspecs.
    refspecs = positionals[1:]
    heads = []
    for rs in refspecs:
        if rs.startswith(':'):
            continue                     # `:branch` / `:refs/heads/x` = delete
        rs = rs.lstrip('+')              # force refspec `+src:dst`
        dst = rs.split(':')[-1]          # `src:dst` → dst; bare `branch` → branch
        if dst.startswith('refs/heads/'):
            dst = dst[len('refs/heads/'):]
        if not dst or dst == 'HEAD' or dst.startswith('refs/'):
            continue                     # tags / non-branch refs / HEAD → fallback
        heads.append(dst)
    # n_refspecs lets the caller tell `git push` (no refspec → current-branch
    # fallback) apart from `git push origin :gone` / tag pushes (refspecs
    # present but nothing trackable → track nothing, don't mistrack current).
    return heads, len(refspecs)

def extract_gh_api_pulls(args):
    has_post = has_pulls = False
    heads = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == '-X' and i + 1 < len(args) and args[i+1].upper() == 'POST':
            has_post = True
        elif a.upper() == '-XPOST' or a == '--method=POST':
            has_post = True
        elif '/pulls' in a:
            has_pulls = True
        if a == '-f' and i + 1 < len(args):
            v = args[i+1]
            if v.startswith('head='):
                heads.append(v[5:])
            i += 2; continue
        i += 1
    return heads if (has_post and has_pulls) else []

# Bash allows inline env-var assignments before a command:
#   GH_HOST=foo gh pr create -H feat
#   A=1 B=2 git push
# shlex emits each KEY=VALUE as its own token. Strip them so the dispatch
# below sees `gh`/`git` as sub[0].
#
# Also strip a leading `export`/`declare` keyword + its assignments: a
# separate `export GH_HOST=…` statement on its own line merges into the
# next command's sub because an unquoted newline is whitespace to shlex
# (not a separator we split on). Stripping it recovers the real command,
# e.g. `export GH_HOST=ghe⏎gh pr create -H feat` → `gh pr create -H feat`.
ENV_ASSIGN_RE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*=')
ENV_KEYWORDS = ('export', 'declare', 'local', 'readonly')

def strip_env_prefix(sub):
    i = 0
    while i < len(sub) and (sub[i] in ENV_KEYWORDS or ENV_ASSIGN_RE.match(sub[i])):
        i += 1
    return sub[i:]

cd_override = None
out = []
for sub in subs:
    if not sub:
        continue
    sub = strip_env_prefix(sub)
    if not sub:
        continue
    if sub[0] == 'cd' and len(sub) >= 2:
        cd_override = sub[1]
        continue
    if len(sub) >= 3 and sub[0] == 'gh' and sub[1] == 'pr' and sub[2] == 'create':
        heads, base, draft = extract_gh_create(sub[3:])
        d = '1' if draft else '0'
        if heads:
            for h in heads:
                out.append((cd_override or '', h, base, 'create', d))
        else:
            out.append((cd_override or '', '', base, 'create', d))
    elif len(sub) >= 2 and sub[0] == 'gh' and sub[1] == 'api':
        for h in extract_gh_api_pulls(sub[2:]):
            out.append((cd_override or '', h, '', 'api', '0'))
    elif len(sub) >= 2 and sub[0] == 'git' and sub[1] == 'push':
        heads, n_refspecs = extract_git_push(sub[2:])
        if heads:
            for h in heads:
                out.append((cd_override or '', h, '', 'push', '0'))
        elif n_refspecs == 0:
            # Plain `git push` / `git push origin` → current-branch fallback.
            out.append((cd_override or '', '', '', 'push', '0'))
        # else: explicit refspecs but all deletes/tags → track nothing.

# Emit 5 TAB-separated fields: repo, head, base, kind, draft. Dedup on
# (repo, head, kind) so a batched `create && create` of distinct heads is
# kept but exact repeats collapse.
seen = set()
for repo, head, base, kind, draft in out:
    key = (repo, head, kind)
    if key in seen:
        continue
    seen.add(key)
    print(f"{repo}\t{head}\t{base}\t{kind}\t{draft}")
PY
)
else
  exit 0
fi

[ -z "$pairs" ] && exit 0

state_ensure_dirs

# PR URLs the create command already printed, in command order. Matching
# `gh pr create` (bare URL) and `gh api .../pulls` (URL inside JSON); the
# char class stops at quotes/commas/whitespace so JSON delimiters don't leak
# into the captured URL.
declare -a stdout_urls=()
if [ -n "$create_stdout" ]; then
  mapfile -t stdout_urls < <(printf '%s' "$create_stdout" \
    | grep -oE 'https?://[^"[:space:],]+/(pull|pr)/[0-9]+')
fi
url_idx=0

while IFS= read -r line; do
  # Manual sequential split — `IFS=$'\t' read` collapses leading/empty fields
  # because tab is IFS-whitespace, and both repo_override and base are often
  # empty.
  repo_override="${line%%$'\t'*}"; rest="${line#*$'\t'}"
  head_branch="${rest%%$'\t'*}";   rest="${rest#*$'\t'}"
  base_cmd="${rest%%$'\t'*}";      rest="${rest#*$'\t'}"
  kind="${rest%%$'\t'*}";          draft="${rest##*$'\t'}"

  case "$kind" in create|api) is_create=1 ;; *) is_create=0 ;; esac

  pair_cwd="${repo_override:-$cwd}"
  repo_root=$(git -C "$pair_cwd" rev-parse --show-toplevel 2>/dev/null)
  [ -z "$repo_root" ] && repo_root="$pair_cwd"

  if [ -z "$head_branch" ]; then
    head_branch=$(git -C "$repo_root" --no-optional-locks symbolic-ref --short HEAD 2>/dev/null)
    [ -z "$head_branch" ] && continue
  fi

  # Consume one stdout URL per create-ish command, in command order.
  url_from_stdout=""
  if [ "$is_create" = "1" ] && [ "$url_idx" -lt "${#stdout_urls[@]}" ]; then
    url_from_stdout="${stdout_urls[$url_idx]}"
    url_idx=$((url_idx + 1))
  fi

  # On create-ish commands the PR definitely exists, so a missing view is
  # read-replica lag, not absence → retry. Plain pushes view once so a branch
  # with genuinely no PR isn't slowed by pointless retries.
  view_attempts=1; [ "$is_create" = "1" ] && view_attempts=3
  gh_pr_view_full "$head_branch" "$repo_root" "$view_attempts"; view_rc=$?

  if [ "$view_rc" -eq 0 ] && [ -n "$PR_URL" ] && pr_is_alive "$PR_STATE"; then
    upsert_pr_state "$session_key" "$repo_root" "$PR_HEAD" "$PR_URL" "$PR_BASE" "$PR_NUMBER"
    echo "[pr-statusline] tracked PR $PR_URL — next step: spawn a background agent running /babysit-ci $PR_URL to monitor CI now, rather than waiting until the turn ends." >&2
  elif [ -n "$url_from_stdout" ]; then
    # View raced (404/empty) but the create command already printed the URL →
    # reconstruct the row instead of dropping silently. base is from the
    # command's -B/--base (empty if it used the repo default — degrades only
    # the stack sort, not tracking); number is parsed from the URL.
    pr_num=$(printf '%s' "$url_from_stdout" | sed -nE 's,.*/([0-9]+)/?$,\1,p')
    upsert_pr_state "$session_key" "$repo_root" "$head_branch" "$url_from_stdout" "$base_cmd" "$pr_num"
    dbg "bash create stdout fast-path: gh pr view raced (rc=$view_rc state='$PR_STATE') for head='$head_branch'; tracked from stdout url=$url_from_stdout base='$base_cmd'"
    echo "[pr-statusline] tracked PR $url_from_stdout — next step: spawn a background agent running /babysit-ci $url_from_stdout to monitor CI now, rather than waiting until the turn ends." >&2
  elif [ "$is_create" = "1" ]; then
    # A PR was just created but neither view nor stdout yielded a URL. This
    # was a 100% silent drop before — surface it so it's self-diagnosing.
    dbg "bash create drop: no URL from view (rc=$view_rc state='$PR_STATE') or stdout for head='$head_branch' repo='$repo_root' kind='$kind'"
    echo "[pr-statusline] could not auto-track just-created PR for $head_branch (read-replica lag?) — run /refresh-pr-state once it is visible" >&2
  else
    # Plain push to a branch with no PR (or none yet) — nothing to track.
    dbg "no PR for pushed branch head='$head_branch' repo='$repo_root' (kind='${kind:-push}')"
  fi
done <<< "$pairs"

exit 0

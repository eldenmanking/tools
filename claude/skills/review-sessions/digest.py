#!/usr/bin/env python3
"""Condense Claude Code session transcripts (~/.claude/projects/*/*.jsonl) for review.

  digest.py list   [--root DIR] [--project SUBSTR] [--days N] [--limit N] [--exclude ID]
  digest.py show   SESSION [--root DIR] [--width N]
  digest.py skills [--root DIR] [--project SUBSTR] [--days N] [--exclude ID]

SESSION is a session id (or unique prefix) or a path to a .jsonl file.
"""
import argparse
import json
import os
import re
import sys
import time

DEFAULT_ROOT = os.path.expanduser("~/.claude/projects")

# Heuristic markers that a human message is steering or correcting rather than starting new work.
CORRECTION_RE = re.compile(
    r"^\s*(no|nope)\b|\b(actually|instead|wrong|not what|that's not|don't|do not|stop|revert|undo|"
    r"still|again|didn't|doesn't|isn't|should(n't)? have|you forgot|you missed|why did you)\b",
    re.IGNORECASE,
)
INTERRUPT_RE = re.compile(r"^\[Request interrupted by user")
COMMAND_RE = re.compile(r"<command-name>/?([^<\s]+)</command-name>")
SKILL_FILE_RE = re.compile(r"/skills/([^/]+)/SKILL\.md$")
# Harness-injected text that arrives as a user message but wasn't typed by the human.
NOISE_PREFIXES = ("<local-command-stdout>", "<local-command-stderr>", "<system-reminder>", "Caveat:")


def load(path):
    entries = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                entries.append(json.loads(line))
            except ValueError:
                continue
    return entries


def text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    return ""


def is_tool_result(content):
    return isinstance(content, list) and any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content)


def human_text(e):
    """The text a human typed (or a slash command they ran), else None."""
    if e.get("type") != "user" or e.get("isSidechain") or e.get("isMeta"):
        return None
    origin = e.get("origin")
    if isinstance(origin, dict) and origin.get("kind") not in (None, "human"):
        return None
    content = (e.get("message") or {}).get("content")
    if is_tool_result(content):
        return None
    text = text_of(content).strip()
    if not text or text.startswith(NOISE_PREFIXES):
        return None
    return text


def skill_from_tool(block):
    """Skill name if this tool_use block invokes or loads a skill."""
    name, inp = block.get("name"), block.get("input") or {}
    if name == "Skill":
        return inp.get("skill") or inp.get("command")
    if name == "Read":
        m = SKILL_FILE_RE.search(inp.get("file_path", ""))
        return m.group(1) if m else None
    return None


def short_input(name, inp):
    for key in ("command", "file_path", "pattern", "skill", "url", "query", "description", "prompt"):
        if key in inp:
            return str(inp[key])
    return json.dumps(inp)[:200]


def one_line(s, width):
    s = " ".join(str(s).split())
    return s if len(s) <= width else s[: width - 1] + "…"


def events(entries):
    """Normalize a transcript into (kind, text) events in order.

    kind: human | command | interrupt | assistant | tool | error | skill
    """
    out = []
    for e in entries:
        if e.get("isSidechain"):
            continue
        t = e.get("type")
        if t == "user":
            content = (e.get("message") or {}).get("content")
            if is_tool_result(content):
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result" and b.get("is_error"):
                        out.append(("error", text_of(b.get("content")) or str(b.get("content"))))
                continue
            text = human_text(e)
            if text is None:
                continue
            m = COMMAND_RE.search(text)
            if m:
                out.append(("skill", m.group(1)))
                out.append(("command", text))
            elif INTERRUPT_RE.match(text):
                out.append(("interrupt", text))
            else:
                out.append(("human", text))
        elif t == "assistant":
            for b in (e.get("message") or {}).get("content") or []:
                if not isinstance(b, dict):
                    continue
                if b.get("type") == "text" and b.get("text", "").strip():
                    out.append(("assistant", b["text"]))
                elif b.get("type") == "tool_use":
                    skill = skill_from_tool(b)
                    if skill:
                        out.append(("skill", skill))
                    out.append(("tool", "%s: %s" % (b.get("name"), short_input(b.get("name"), b.get("input") or {}))))
    return out


def skill_runs(evts):
    """For each skill use, measure the steering that followed it before the next skill or session end."""
    runs = []
    for i, (kind, val) in enumerate(evts):
        if kind != "skill":
            continue
        run = {"skill": val, "human_after": 0, "corrections": [], "interrupts": 0, "errors": 0, "tools_after": 0}
        for kind2, val2 in evts[i + 1:]:
            if kind2 == "skill":
                break
            if kind2 == "human":
                run["human_after"] += 1
                if CORRECTION_RE.search(val2):
                    run["corrections"].append(val2)
            elif kind2 == "interrupt":
                run["interrupts"] += 1
            elif kind2 == "error":
                run["errors"] += 1
            elif kind2 == "tool":
                run["tools_after"] += 1
        runs.append(run)
    return runs


def meta(path, entries):
    first = next((e for e in entries if e.get("timestamp")), {})
    last = next((e for e in reversed(entries) if e.get("timestamp")), {})
    evts = events(entries)
    prompts = [v for k, v in evts if k in ("human", "command")]
    return {
        "id": os.path.basename(path)[:-len(".jsonl")],
        "path": path,
        "project": os.path.basename(os.path.dirname(path)),
        "cwd": next((e["cwd"] for e in entries if e.get("cwd")), ""),
        "branch": next((e["gitBranch"] for e in entries if e.get("gitBranch")), ""),
        "start": first.get("timestamp", ""),
        "end": last.get("timestamp", ""),
        "human_turns": len(prompts),
        "interrupts": sum(1 for k, _ in evts if k == "interrupt"),
        "errors": sum(1 for k, _ in evts if k == "error"),
        "skills": sorted({v for k, v in evts if k == "skill"}),
        "first_prompt": prompts[0] if prompts else "",
        "events": evts,
    }


def find_sessions(root, project=None, days=None, exclude=None):
    cutoff = time.time() - days * 86400 if days else 0
    paths = []
    for proj in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        if project and project not in proj:
            continue
        pdir = os.path.join(root, proj)
        if not os.path.isdir(pdir):
            continue
        for name in os.listdir(pdir):
            p = os.path.join(pdir, name)
            if not name.endswith(".jsonl") or os.path.getmtime(p) < cutoff:
                continue
            if exclude and name.startswith(exclude):
                continue
            paths.append(p)
    paths.sort(key=os.path.getmtime, reverse=True)
    return paths


def resolve(root, session):
    if os.path.isfile(session):
        return session
    matches = [p for p in find_sessions(root) if os.path.basename(p).startswith(session)]
    if len(matches) != 1:
        sys.exit("%d sessions match %r" % (len(matches), session))
    return matches[0]


def cmd_list(args):
    paths = find_sessions(args.root, args.project, args.days, args.exclude)[: args.limit]
    if not paths:
        print("No sessions under %s" % args.root)
    for p in paths:
        m = meta(p, load(p))
        if not m["human_turns"]:
            continue
        print("%s  %s  %s" % (m["id"], m["start"][:16], m["cwd"] or m["project"]))
        print("    turns=%d interrupts=%d errors=%d branch=%s skills=%s"
              % (m["human_turns"], m["interrupts"], m["errors"], m["branch"] or "-", ",".join(m["skills"]) or "-"))
        print("    > %s" % one_line(m["first_prompt"], 160))


def cmd_show(args):
    path = resolve(args.root, args.session)
    m = meta(path, load(path))
    print("# %s\ncwd=%s branch=%s start=%s end=%s turns=%d interrupts=%d errors=%d\n"
          % (m["id"], m["cwd"], m["branch"], m["start"], m["end"], m["human_turns"], m["interrupts"], m["errors"]))
    labels = {"human": "USER", "command": "CMD", "interrupt": "INTERRUPT", "assistant": "CLAUDE",
              "tool": "  tool", "error": "  ERROR", "skill": "SKILL"}
    for kind, val in m["events"]:
        # Human words are the main signal, so keep them whole; abbreviate everything else.
        width = args.width * 4 if kind in ("human", "command") else args.width
        print("%s: %s" % (labels[kind], one_line(val, width)))


def cmd_skills(args):
    totals = {}
    for p in find_sessions(args.root, args.project, args.days, args.exclude):
        m = meta(p, load(p))
        for run in skill_runs(m["events"]):
            t = totals.setdefault(run["skill"], {"uses": 0, "human_after": 0, "corrections": 0,
                                                 "interrupts": 0, "errors": 0, "sessions": []})
            t["uses"] += 1
            for k in ("human_after", "interrupts", "errors"):
                t[k] += run[k]
            t["corrections"] += len(run["corrections"])
            t["sessions"].append((m["id"], run))
    if not totals:
        print("No skill usage found.")
    for skill, t in sorted(totals.items(), key=lambda kv: -(kv[1]["corrections"] + kv[1]["interrupts"])):
        print("%s  uses=%d human_after=%d corrections=%d interrupts=%d errors=%d"
              % (skill, t["uses"], t["human_after"], t["corrections"], t["interrupts"], t["errors"]))
        for sid, run in t["sessions"]:
            if run["corrections"] or run["interrupts"]:
                print("    %s  human_after=%d tools_after=%d" % (sid, run["human_after"], run["tools_after"]))
                for c in run["corrections"][:5]:
                    print("      > %s" % one_line(c, 200))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("list", "show", "skills"):
        p = sub.add_parser(name)
        p.add_argument("--root", default=DEFAULT_ROOT)
        if name == "show":
            p.add_argument("session")
            p.add_argument("--width", type=int, default=200)
        else:
            p.add_argument("--project", help="substring of the project directory name (cwd with / replaced by -)")
            p.add_argument("--days", type=float, help="only sessions modified in the last N days")
            p.add_argument("--exclude", help="session id (prefix) to skip, e.g. the current session")
        if name == "list":
            p.add_argument("--limit", type=int, default=30)
    args = ap.parse_args()
    {"list": cmd_list, "show": cmd_show, "skills": cmd_skills}[args.cmd](args)


if __name__ == "__main__":
    main()

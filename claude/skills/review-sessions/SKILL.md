---
name: review-sessions
description: Review past Claude Code session transcripts to find context worth adding to CLAUDE.md files, repeated workflows worth turning into skills, and existing skills that needed heavy follow-up steering to reach the intended outcome. Proposes concrete edits and applies only the ones the user approves.
argument-hint: "[--days N] [--project SUBSTR] [focus, e.g. 'skills only' or 'dotfiles repo']"
allowed-tools: Bash, Read, Grep, Glob, Edit, Write, Agent, AskUserQuestion
---

# Review sessions

You are reviewing how past sessions went so future ones need less steering. The evidence is what the user typed: corrections, repeated explanations, interruptions and follow-up asks. Every recommendation must cite the sessions it comes from.

Transcripts live in `~/.claude/projects/<project>/<session-id>.jsonl` (project = cwd with `/` replaced by `-`). Don't read them raw: they are large and mostly tool output. Use the helper next to this file (`~/.claude/skills/review-sessions/digest.py`; if this skill was installed elsewhere, use the copy beside this SKILL.md):

```bash
D=~/.claude/skills/review-sessions/digest.py
python3 $D list   [--days N] [--project SUBSTR] [--limit N] [--exclude ID]  # one entry per session: turns, interrupts, errors, skills, first prompt
python3 $D show   <session-id-prefix> [--width N]                          # condensed timeline: USER / CMD / INTERRUPT / CLAUDE / tool / ERROR / SKILL lines
python3 $D skills [--days N] [--project SUBSTR] [--exclude ID]             # per-skill uses and the steering that followed each use
```

## Step 1: Scope

Parse `$ARGUMENTS` for `--days`, `--project` and any focus. Default to the last 14 days across all projects. Exclude the current session (the most recently modified transcript whose first prompt invoked this skill) with `--exclude`.

Run `list`. If `~/.claude/projects` has no sessions besides this one (common in Claude Code on the web, where each container starts fresh), say so and stop; suggest running the skill locally.

## Step 2: Gather context

Before judging gaps, know what's already written down, so you don't propose rules that already exist:
- Global instructions: `~/.claude/CLAUDE.md` and any `CLAUDE.md` it is inserted into.
- For each project cwd in scope that still exists: its `CLAUDE.md`, `.claude/CLAUDE.md` and `.claude/skills/`.
- Installed skills: `~/.claude/skills/*/SKILL.md`. Run `ls -l ~/.claude/skills` to see which are symlinks into a tools repo; edits go to the symlink target, in that repo, not the link.

## Step 3: Read the sessions

Prioritize sessions with many turns, interrupts, errors or skills. Read them with `show`. For more than ~10 sessions, split them across `Agent` subagents (a few sessions each), giving each this step's checklist and asking for findings with session ids and quoted user messages, not summaries of what happened.

Look for:
1. **Missing context.** The user explaining the same fact, preference or convention more than once, within or across sessions ("we use pnpm here", "tests live in...", "don't touch generated files"). Claude repeatedly discovering the same thing by trial and error (the same failing command, then the same fix). Corrections of Claude's default behavior. Each is a candidate line for a CLAUDE.md.
2. **Workflow candidates.** A multi-step sequence the user asked for in two or more sessions with similar wording or the same tool sequence (release steps, a review routine, a data pull, environment setup). Long first prompts that spell out a procedure are strong candidates.
3. **Skill iteration.** Run `skills`, then `show` each flagged session and read from the `SKILL:` line on. Decide whether the follow-up was steering the skill's output (the skill fell short) or just new work. Note what the user had to add, correct or redo, and which step of the SKILL.md was missing, wrong or ignored. A skill that is often interrupted or followed by the same correction needs an edit; a skill that is never used may need a better `description`.

Treat the heuristics (`corrections=`, `interrupts=`) as pointers to read, not as findings. Ignore one-off requests, and anything already covered by the files from Step 2 unless Claude ignored it (then the fix is wording or placement, not a new rule).

## Step 4: Report

Present findings grouped as below, most impactful first. For each: the evidence (session ids, a short quote of the user), and the exact proposed change.

```
## CLAUDE.md additions
1. <file to edit> — <proposed line(s)>
   Evidence: <session> "<quote>"; <session> "<quote>"

## New skills
1. <name> — <what it does, when it triggers>
   Evidence: <sessions and the repeated steps>
   Outline: <steps the SKILL.md would contain>

## Existing skill fixes
1. <skill> (<uses> uses, <n> needed steering) — <what to change in which step>
   Evidence: <session> "<quote of the correction>"

## Not acting on
- <patterns seen once, or already covered>
```

Pick the narrowest file for each addition: a repo's `CLAUDE.md` for repo facts, the global one only for preferences that held across repos. Keep proposed lines in the style and density of the target file. Don't quote secrets, tokens or private data from transcripts in the report or in proposed edits.

## Step 5: Apply

Ask the user (`AskUserQuestion`, multi-select) which items to apply. Apply only those. For new skills, write `<skills dir>/<name>/SKILL.md` with `name`, `description` and, where useful, `argument-hint` and `allowed-tools` frontmatter, matching the existing skills in that repo; mention any README or install steps the repo requires. If the edited files are in a git repo, list the changed files and offer to commit them, following the repo's commit conventions.

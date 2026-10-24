# tools
Tools, investigations, and useful AI config. Shared Claude Code configuration: the global CLAUDE.md, skills, hook and statusline scripts, and settings. The [dotfiles](https://github.com/eldenmanking/dotfiles) installer (`./install.sh --variant <name> --claude`) clones this repo and installs it into `~/.claude`. To use a fork or local checkout instead, see the dotfiles README.

```
.
├── .claude
│   └── CLAUDE.md          --- Claude Code instructions for working in this repo
├── claude
│   ├── CLAUDE.md          --- global instructions, inserted into ~/git/.claude/CLAUDE.md between "Added by dotfiles" markers
│   ├── scripts            --- hook/statusline scripts, each symlinked into ~/.claude/scripts
│   ├── settings.json      --- hooks, statusline and permissions, deep-merged into ~/.claude/settings.json
│   └── skills             --- skills, each directory symlinked into ~/.claude/skills
├── docs
│   └── PR-STATUSLINE.md   --- design of the PR statusline (scripts + skills)
└── tests
    └── claude-pr-statusline
```

## Adding a skill
Create `claude/skills/<name>/SKILL.md` with `name` and `description` frontmatter, then rerun the dotfiles installer. To make a skill available in Claude Code on the web, zip its directory and upload it to your claude.ai account; skills in `~/.claude/skills` don't persist in cloud sessions.

## Tests
```bash
bash tests/claude-pr-statusline/run.sh
```

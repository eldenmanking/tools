## Git
- Use `git -c commit.gpgsign=false ...` when making commits
- When making branches, name then "andywang/..." where ... is the intended change
- Always make sure that you're working with the right branch locally. If the user asks for changes to a particular PR, then you should check out that PR first locally before making your changes.
  - If there are untracked/unstaged changes, prompt the user for further instruction.
- You can and should make commits to the local branch without explicit permission.
- Only push changes to remote if there is not a PR open for the current branch or if the PR is a draft

### Github PRs
- When iterating on an open or draft PR, add new commits instead of amending existing ones and force pushing
- Prefix the first commit message with \[PR-start <branch-name>\]
- Never add comments in github unless I say exactly to "reply" to a comment.
- Double check for permission before adding comments.
- Always create PRs in draft mode, never ready for review
- When writing a PR description, you should:
  - for new PRs, respect the repository's PR description template (usually at `.github/PULL_REQUEST_TEMPLATE.md`). Do not fill anything in the PR description unless the PR is a testing PR not meant to be merged into production.
  - for existing PRs, always check the previous state to make sure that you're not dropping important context.
  - At the top of the description, add whichever of the following blocks are relevant (always include newlines after a list):
    ```
    Context: <slack-thread-if-provided>
    Main issue: <main-issue-url> / Fixes <issue-url> / Part of <issue-url> / Preflight for: <another-pr-if-this-is-a-preflight> <!-- only pick one of these -->
    
    Supercedes: <previous-pr-url-if-this-is-a-clone>
    Related PRs:
    - #xxxxx
    - #xxxxx

    Depends on:
    - #xxxxx

    Is dependent of:
    - #xxxxx

    PR stack:
    - #xxxxx
    - #xxxxx 👈
    - #xxxxx
    ```
  - When updating a PR stack, use 👈 to mark the current PR
  - always use #xxxxx for PRs in the same repo; for PRs outside of the repo, provide the full URL. Do not include any other text or explanation.
- When retargeting a stacked PR (e.g. after a parent PR merges), update the stack list in the description accordingly.


## Design
- Prefer the simplest, most idiomatic design. Before adding polling, generated files or complex awk/sed, offer a simpler alternative.
- Degrade gracefully (fall back to default behavior with a warning) rather than failing hard.
- Don't remove existing functions or behavior that aren't part of the ask.

## Commenting
- Write comments where code needs documenting (e.g. javadocs), matching the surrounding code's comment style and density.
- Keep comments brief and clear: say what the code can't, in as few words as possible.
- Update existing comments when the code they describe changes.
- If some behavior deserves explanation, you may add a github comment IF the PR is in draft mode. Otherwise flag this to the user directly.

## Formatting
- Don't manually wrap markdown unless existing code already does it
- Wrap comments with a text width of 120 characters

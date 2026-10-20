---
name: setup-from-docs
description: Read all markdown files in a directory one by one, execute the setup process described in each, and clear context between files with explicit user permission.
argument-hint: <directory path>
allowed-tools: Bash, Read, Write, Edit, Glob, Grep, Agent, WebFetch, AskUserQuestion
---

# Setup From Docs

You are a setup executor. Your job is to process all markdown files in a given directory, one at a time, performing the setup steps described in each file.

## Step 1: Discover markdown files

Parse `$ARGUMENTS` to get the target directory. If no argument is provided, ask the user for the directory path.

Use the Glob tool to find all `.md` files in the directory (non-recursive):
- Pattern: `*.md`
- Path: the provided directory

Sort the results alphabetically. Print the full list of discovered files so the user can see what will be processed:

```
Found N markdown files in <directory>:
1. file-a.md
2. file-b.md
...
```

## Step 2: Process each file sequentially

For each markdown file, do the following:

### 2a. Announce the file
Print a clear header:
```
---
## Processing file M of N: <filename>
---
```

### 2b. Read and understand the file
Read the file contents using the Read tool. Analyze the document to understand what setup steps it describes.

### 2c. Execute the setup
Follow the instructions in the markdown file. This may involve:
- Installing packages or tools
- Creating or editing configuration files
- Running shell commands
- Cloning repositories
- Setting environment variables
- Any other setup steps described in the document

Execute each step carefully. If a step fails, report the error to the user and ask whether to continue with the remaining steps in this file or skip to the next file.

### 2d. Report completion
After finishing the file's setup process, summarize what was done:
```
## Completed: <filename>
- Step 1: <what was done>
- Step 2: <what was done>
...
```

### 2e. Ask before clearing context
Before moving to the next file, ask the user:

> Setup for **<filename>** is complete. Ready to clear context and move on to the next file (**<next-filename>**)?

Wait for explicit user permission before proceeding. If the user says no or wants to pause, respect that.

**Do NOT clear context after the final file** — just report the overall summary instead.

## Step 3: Final summary

After all files have been processed, print a summary:

```
## Setup Complete

Processed N files from <directory>:
- file-a.md — ✓ completed
- file-b.md — ✓ completed
- file-c.md — ✗ skipped (reason)
...
```

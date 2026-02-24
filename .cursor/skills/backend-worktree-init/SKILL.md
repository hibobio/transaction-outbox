---
name: backend-worktree-init
description: Guides initialization of backend-repositories when working in Cursor with git worktree. Use when the repo appears uninitialized (missing submodule dirs), when the user mentions worktree or uninitialized repo, or when source code for a submodule is not found. Ensures README is read first, only required submodules are initialized (avoid lengthy full init), and when in a worktree all paths stay inside the worktree.
---

# Backend worktree init

## When to use

Apply this skill when working in backend-repositories with Cursor and/or git worktree, when the repo may be uninitialized, or when source code for a submodule is not found.

## Instructions

### 1. Recognize uninitialized state

When working in backend-repositories with Cursor and/or git worktree, assume the repo may be uninitialized: submodule paths from `.gitmodules` may be missing or empty. Source code for those repos is not available until they are initialized.

### 2. Worktree: paths must stay inside the worktree

When working in a Git worktree, the agent must ensure **all paths are always inside the current worktree** (e.g. file reads, terminal `cd`, script paths, submodule paths). Do not assume or use the main working tree path; use the worktree root as the base so that all operations stay within this worktree.

### 3. Read README first

Read README.md at the root of backend-repositories repo to understand:

- Bob CLI (`source bob`, `bobInfo`, `bobInit`)
- Submodules and that full init is done via `bobInit` / `git submodule update --init --recursive`
- That full init is lengthy; prefer initializing only what is needed

### 4. Initialize only required submodules

- Determine which submodule(s) are **required for the current task** (from user request or context, e.g. "libraries", "files", "metadata-repo"). If ambiguous, ask the user which repo(s) they need.
- For each required `<path>` (must match a `path` in `.gitmodules`):

  ```bash
  git submodule update --init --recursive <path>
  ```

- Do **not** run `bobInit` or `git submodule update --init --recursive` (no path) unless the user explicitly requests full init / all repos.

### 5. After init

Once the needed submodules are initialized, their source code is available under the repo root. Proceed with the user's task. If later the user needs another submodule, run `git submodule update --init --recursive <path>` for that path.

## Bob commands

If the user will run bob commands (e.g. `bobGitSubmoduleForeach`), they should `source bob` in the shell. For init-only, `git submodule update --init --recursive <path>` is sufficient.

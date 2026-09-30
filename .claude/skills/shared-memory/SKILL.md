---
name: shared-memory
description: Read and maintain the repository's shared memory in memory/, the decisions, gotchas, workflow and owner preferences learned while working on Skill Atlas. Use before starting any task (read) and after finishing one, or whenever you learn something non-obvious (update).
---

# Shared memory

`memory/` is what agents and people have learned working on this repository. The spec
(`specs/spec.md`) says what the product does; memory says why it is that way and what
surprised us. It is shared: every agent reads it, and every agent adds to it.

## Before a task: read

1. Read `memory/README.md`, then every file it lists. They are short on purpose.
2. Apply what's relevant, especially `gotchas.md` before you touch the build, git, the
   sandbox, or Mosaic, and `workflow.md` for the definition of done.
3. If memory contradicts the code or the spec, trust the code and the spec. Fix the memory
   entry as part of your task.

## After a task: update

Update memory when you learned something a future agent would otherwise have to rediscover:
- a decision and the reason for it, or an option you rejected
- a trap: an error message, what caused it, and the fix
- a change to how we work, or to what the owner prefers

Don't record things that are already obvious from the code, the spec or `git log`. That
includes file lists, what a function does, or a summary of the PR.

How to update:
1. **Pick the file** by topic: `decisions.md`, `gotchas.md`, `workflow.md`, or `preferences.md`.
   Add a new topic file only when none fits, and list it in `memory/README.md`.
2. **Check for an existing entry** on the same subject. Edit that entry rather than adding a
   near-duplicate. If something turned out to be wrong, correct or delete the entry.
3. **Write the entry** in this format, dated with today's absolute date:

   ```markdown
   ## <short title> (YYYY-MM-DD)

   <the fact, one to three sentences, with exact commands, error texts, or file paths>

   **Why:** <the reason, or the incident that taught us>
   **How to apply:** <what to do differently next time>
   ```

4. **Keep it safe to share.** No tokens, keys, passwords, or personal data. Refer to people
   by role, e.g. "the owner".
5. **Commit memory with the change it came from,** in the same PR, so reviewers see both
   together.

## Keep it useful

- **Short entries:** if an entry needs more than about ten lines, the detail probably
  belongs in the spec or in a code comment. Link to it instead.
- **Newest entry last** within a file, and each file under about 300 lines. When a file
  grows past that, merge related entries.

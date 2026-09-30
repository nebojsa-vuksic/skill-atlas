# Workflow

## Spec first, then code (2026-09-30)

Every feature starts by updating `specs/spec.md` with the behavior, the outputs, the test
scenarios in section 11.2, and the acceptance criteria in section 10. The code comes after.

**Why:** the owner asks for "update the spec, then implement" every time, and reviews the
spec.
**How to apply:** when a request is ambiguous, write down the chosen reading in the spec and
say so in the reply.

## Definition of done (2026-09-30)

A change is done when all of the following hold (spec section 12):

1. `./gradlew build` passes locally, with no skipped tests. That includes unit tests,
   integration tests, pseudo-terminal tests, and browser tests.
2. A PR is open, created with `gh pr create --base main`.
3. CI is green on the PR's **latest** commit, for both the `push` and `pull_request` runs,
   on Ubuntu and macOS.
4. The PR is merged with `gh pr merge <n> --merge --delete-branch`, and CI on `main` is green
   afterwards.

When CI is red, read the logs with `gh run view <id> --log-failed`, fix the root cause,
and push again. Never loosen or skip a test.

**How to apply:** check which SHA the green runs are for before merging.

## Tests are deterministic (2026-09-30)

Integration tests run the installed launcher against a stub GitHub API on 127.0.0.1 and
local fixture repos with fixed git identities and dates, in an isolated environment.
`pty_run.py` waits for text on screen before sending keys. Nothing waits on a fixed sleep.

**How to apply:** a new feature gets exact-output integration tests. Put the harness in
`Sandbox.kt`.

## Parallel features: one per sandbox (2026-09-30)

Each feature gets its own worktree, branch, and sandbox, and its own PR. When CI goes green
on the first PR, merge it. For each PR after that, run `git merge origin/main` into it,
wait for CI to go green again, then merge it (spec section 12.1).

Commands used:
- `git worktree add -b feature/<x> ../atlas-<x> origin/main`, then put the brief in
  `TASK.md`, which `.git/info/exclude` ignores.
- `sbx run -d --name atlas-<x> -e ANTHROPIC_API_KEY=… -e ANTHROPIC_BASE_URL=http://host.docker.internal:19516/wire/<key>/claude-code/anthropic claude ../atlas-<x> .git`
- `sbx exec atlas-<x> bash -lc 'cd <worktree> && claude -p "<prompt>" --dangerously-skip-permissions --output-format stream-json --verbose'`

**Why:** the filter and the similar skills were built this way, as PRs #7 and #8.
**How to apply:** give each brief a scope that doesn't overlap the other's files. Add
cross-feature tests while merging. The "clear the filter from similar skills" test only
became possible once both features were on one branch. The Claude Code auto-mode
classifier may block starting an agent with `--dangerously-skip-permissions`. Ask the owner
first, and don't work around the block.

## Verify it for real, not only in tests (2026-09-30)

After tests pass, run the real thing against real repos. We use `anthropics/skills`,
`JetBrains/MPS`, `JetBrains/koog`, `JetBrains/android`, and `jetbrains/kotlin`. For the web
view, take headless Chromium screenshots with Playwright from `/tmp/sa-pw`. For the
terminal views, drive them in a pty.

**Why:** real runs caught the 40 s clone, the 20-column description bug under `script`,
lost indentation in `browse`, and a no-match message pushed out of view.

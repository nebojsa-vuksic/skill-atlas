# AGENTS.md

Instructions for AI agents working in this repository. Humans are welcome to read them too.

## Always

- **ALWAYS read `memory/` before a task.** Start with `memory/README.md`, then read every
  file it lists. It holds the decisions, gotchas, workflow and owner preferences learned
  so far. Use the `shared-memory` skill (`.agents/skills/shared-memory/SKILL.md`).
- **ALWAYS update shared memory with the `shared-memory` skill** before you finish a task in
  which you learned something non-obvious. That covers a decision and why it was made, a
  trap and its fix, or a change in how we work. Commit it in the same PR as the change.
- **Read the spec first:** `specs/spec.md` is the source of truth for behavior. Change the
  spec before, or together with, the code.
- **Every PR follows `.github/pull_request_template.md`.** When users can see the change,
  record a scripted demo with the `record-demo` skill (`.agents/skills/record-demo/SKILL.md`)
  and embed it in the PR (spec section 13).

## Project

Skill Atlas lists the agent skills (`SKILL.md` files) in a GitHub repository. It runs as a
Kotlin/JVM CLI with four commands:
- `scan`, with `--filter` and `--skill`
- `browse`, a Mosaic terminal UI
- `serve`, a local web view
- `shell`, an interactive slash-command shell with a command palette, which is also what
  `skill-atlas` with no arguments opens in a terminal

```
./gradlew build                       # everything: unit, CLI integration, pty and browser tests
./gradlew test                        # unit tests only
./gradlew integrationTest             # runs the installed launcher; needs git and python3
./gradlew installDist                 # then: build/install/skill-atlas/bin/skill-atlas <command>
GITHUB_TOKEN=$(gh auth token) build/install/skill-atlas/bin/skill-atlas scan https://github.com/anthropics/skills
```

## Definition of done

Spec section 12 has the full version. A change is done only when **all** of these hold:

1. `./gradlew build` passes locally, with no test skipped, disabled, or loosened to make it
   pass.
2. The work is on a branch with a pull request, opened with `gh pr create --base main`.
3. CI (GitHub Actions: Ubuntu and macOS) is green on the PR's **latest** commit. Watch it with
   `gh pr checks <n> --watch`.
4. The PR is merged with `gh pr merge <n> --merge --delete-branch`, and CI on `main` is green
   afterwards.

If CI is red, read `gh run view <run-id> --log-failed`, fix the root cause, and push again.
Repeat until it's green. When several features are built in parallel sandboxes, the second
PR merges `origin/main` in and must go green again before it's merged (spec section 12.1).

## Conventions

- Match the surrounding code: its comment style, naming, and idioms. Exit codes are set only
  in `Main.kt`, through `SkillAtlasException`.
- Text from scanned repositories is untrusted. Terminal output goes through `sanitize`. The
  web page inserts text only, and Markdown is escaped on the server.
- Tests are deterministic. There is no network: a stub API and local fixture repos stand in.
  Tests wait for markers in the output, never on sleeps.
- Commit messages explain why. Never commit tokens, `TASK.md`, or build output.

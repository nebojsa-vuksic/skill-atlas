# Preferences of the project owner

## Merge when green, without asking again (2026-09-30)

Once a PR's CI is green on its latest commit, merge it. When CI is red, read the logs, fix
the problem, and push again.

**Why:** this is stated in the definition of done, and the owner repeated it: "When CI on
PR is green merge."

## Short status answers, with the facts (2026-09-30)

When the owner asks "status?" or "ping", give the state of each agent or PR: what it did
last, what's next, and the SHA and CI result. Say why something is slow.

## Ask before system-level changes (2026-09-30)

Installing tools (brew taps, casks), changing sandbox network policies, and starting
autonomous agents are confirmed with the owner first. Logins such as `sbx login` and
`gh auth refresh` are done by the owner, with `! <command>`.

## Show the real UI (2026-09-30)

The owner judges features by what they see: the browser page and the terminal view. When
something "isn't there", check for a stale build or server before assuming a bug.

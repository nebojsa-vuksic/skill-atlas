<!--
Fill in every section, and delete the hints. The definition of done is spec section 12 and
AGENTS.md; the checklist at the bottom mirrors it.
-->

## Summary

<!-- What changes for users, in 2–5 bullets. Lead with the behavior, not the files. -->

-

## Spec

<!-- Which sections of specs/spec.md this implements or changes. The spec changes first, or in this PR. -->

- Sections:
- Acceptance criteria:

## Demo

<!--
Required when users can see the change (spec section 13). Record it with a script, never by hand:
  demo/record.sh <name>              # writes build/demo/<name>/
  demo/publish.sh <pr-number> <name> # pushes to the demos branch and prints the Markdown below
Paste the printed Markdown here. Write "No visible change" if there's nothing to show.
-->

## Testing

<!-- Test counts before and after, which new tests cover which spec scenario (section 11.2), and what you ran by hand against real repositories. -->

- `./gradlew build`: … tests, none skipped
- New tests:
- Tried against real repositories:

## Decisions and follow-ups

<!-- Readings of the spec you had to choose, anything left out on purpose, and known limits. Delete this section if there are none. -->

## Checklist

- [ ] The spec is updated (or unchanged, because behavior didn't change)
- [ ] `./gradlew build` passes locally, and no test was skipped, disabled, or loosened
- [ ] The demo is recorded with `demo/record.sh` and published to the `demos` branch (or there's no visible change)
- [ ] `memory/` is updated with the `shared-memory` skill if anything non-obvious was learned
- [ ] CI is green on the latest commit, for both the `push` and `pull_request` runs

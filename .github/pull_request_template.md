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
CI records every demo on every run (spec section 13). Link this PR's run: the `demos`
artifact of its **Demos** job has the videos, screenshots, and any screenshot diffs.
If the UI changed on purpose: add or adjust the demo's key moments (spec 13.3), run the
**Update screenshots** workflow for this branch, and say why the baselines changed under
"Decisions". Write "No visible change" if nothing users see changed.
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
- [ ] The **Demos** job is green: its screenshots match the baselines, or the baselines were updated on purpose (spec 13.5)
- [ ] `memory/` is updated with the `shared-memory` skill if anything non-obvious was learned
- [ ] CI's `pull_request` run is green on the latest commit (Build and Demos)

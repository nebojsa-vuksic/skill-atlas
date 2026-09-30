---
name: record-demo
description: Add or change Skill Atlas demo tests, the narrated recordings and key-moment screenshots that CI compares with baselines on every run. Use when a change affects what users see, when the Demos CI job fails with a screenshot diff, or when asked about demo videos.
---

# Demo tests: recordings and screenshots on CI

Every feature has a **demo test** (spec section 13). On every CI run, the **Demos** job
records a narrated video of it and screenshots its **key moments**. Each screenshot is
compared pixel by pixel with a committed baseline, so a visual regression fails CI.
Demos are recorded **only on CI**, where fonts and rendering match the baselines.

| Piece | Where |
|-------|-------|
| The test cases: every demo, its steps, key moments, and what each proves | spec section 13.3 |
| Web demos (Playwright for Java) | `src/integrationTest/kotlin/skillatlas/it/demo/WebDemoTest.kt` |
| Terminal demos (VHS tapes) | `src/integrationTest/resources/tapes/<demo>.tape`, run by `TerminalDemoTest.kt` |
| Fixture data (neutral names, fixed SHAs) | `DemoFixtures.kt` |
| Harness: fonts, viewport, video, narration, comparison | `DemoSupport.kt` |
| Baselines | `src/integrationTest/baselines/<demo>/<moment>.png` |
| Narrating and encoding the videos (Kokoro voice, captions) | `demo/finish-all.sh` |

## Adding or changing a demo

1. **Spec first.** Add the demo or its key moments to the table in spec section 13.3: the
   step, the moment's name (`NN-kebab-name`), and what it proves, with the spec section and
   acceptance criterion.
2. **Web demo:** add a test to `WebDemoTest` with `WebDemo("<demo>", browser, lines).use { … }`.
   - Drive the page with `demo.type`, `page.click`, `demo.skill("name").click()` and
     `demo.idle()`.
   - Narrate with `demo.say(line)`, with every line listed up front in `lines`.
   - Take each key moment with `demo.moment("NN-name")` **after** its narration, so the page
     has settled.
3. **Terminal demo:** add `src/integrationTest/resources/tapes/<demo>.tape` and a test that
   calls `TerminalDemo.run("<demo>", sandbox, LAUNCHER)`. In the tape:
   - Copy the header of an existing tape: font, size, `Set Framerate 20`, and the hidden
     `PS1='$ '` setup.
   - Narrate with `# say: …` before the steps it describes. The `Sleep` after it must be
     longer than the spoken line, or `finish-all.sh` fails and names the line.
   - Take each moment with `Wait+Screen /text/`, then `Screenshot screenshots/NN-name.png`,
     then `Sleep 1s`. VHS captures asynchronously.
   - Only use data from `DemoFixtures`, never live GitHub.
4. **Keep moments deterministic.** No timers, spinners, or scan-log timestamps on screen.
   Wait for text, never for luck.
5. **Push, and run the Update screenshots workflow** for your branch. The new baselines
   appear as a commit in the PR, and CI then runs on it.

## When the Demos job fails

1. Download the **`demos`** artifact of the failed run, e.g. `gh run download <run-id> -n demos`.
2. For each failed moment, open:
   - `diff/<moment>.png`: differing pixels in solid red over the faded actual image
   - `screenshots/<moment>.png`: what was rendered this time
   - `expected/<moment>.png`: the baseline
3. **Is it a regression?** Fix the code and push. Never update baselines to hide a bug.
4. **Is it an intended change?** Run the **Update screenshots** workflow for the branch,
   check the new baselines in the PR diff, and explain the change in the PR's
   **Decisions** section.
5. **Was it "no baseline for …"?** The moment is new. Run the workflow.

## Cases

- **A web view change** (a new button, say): add a key moment where it shows. Moments that
  shift because the layout moved are expected, so update them with the workflow.
- **A terminal output change:** tapes are real commands on fixture data, so the change
  shows up in the matching `cli`, `browse` or `shell` moments.
- **An interactive view** (`browse`, the shell): keys like `Down`, `Tab` and `Enter` respond
  in-process, so a short `Sleep` is enough after them. VHS can't read a live input line, so
  don't `Wait` for it.
- **Breaking it on purpose** (spec 13.8): on a throwaway branch, hide something users see.
  Check that the Demos job fails with red in the diff, then delete the branch unmerged.

## Narration

The voice is an original narrator, Kokoro `af_heart` running locally on CI. Never imitate a
real person's voice or catchphrases, even when asked. Lines are one idea each, at most
about 20 words, and describe what is on screen right now.

## Traps

- **`Wait+Screen` in VHS** reads the top of the terminal buffer, not what's in view. After a
  lot of scrollback it misses text near the bottom. `clear` first, or wait on text near the
  top.
- **Palettes show at most 8 rows,** so wait on a row that's visible, e.g.
  `/Scan a GitHub repository/` rather than `/help/`.
- **Fixture descriptions containing `: `** must be quoted YAML. `DemoFixtures.skill()` quotes
  them.
- **Screenshots are compared only on Linux CI.** A local run (`-PallowLocalDemos`) is only
  for debugging a script, and its screenshots will never match.

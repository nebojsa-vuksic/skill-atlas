---
name: record-demo
description: Record a scripted demo video of Skill Atlas for a pull request (the web view with Playwright, the terminal with VHS), publish it to the orphan demos branch, and embed it in the PR. Use when a PR changes something users can see, or when asked to record, redo, or attach a demo.
---

# Record a demo for a pull request

Demos are **scripted**, never recorded by hand (spec section 13). Anyone can run the
script again, the result looks the same every time, and no screen-recording
permission is needed. Everything lives in `demo/`:

| File | What it does |
|------|--------------|
| `demo/record.sh <name>` | Builds `installDist`, starts `serve` on a free port, and runs `demo/<name>/web.mjs` and/or `demo/<name>/terminal.tape`. Writes `build/demo/<name>/`, then checks the size limits. |
| `demo/<name>/web.mjs` | A Playwright script. It receives the web view URL and the output `.webm` path. |
| `demo/<name>/terminal.tape` | A VHS script. It writes `terminal.gif` and `terminal.mp4` into the output folder. |
| `demo/publish.sh <pr> <name>` | Pushes the `.gif` and `.mp4` files to the `demos` branch under `demos/pr-<pr>/`, and prints the Markdown for the PR. |

You need `ffmpeg`, plus `vhs` (`brew install vhs`, which also brings `ffmpeg` and `ttyd`),
Node.js, and a `gh` login. `record.sh` gets a `GITHUB_TOKEN` from `gh auth token`, and it
uses a temporary scan log and stars file, so demo scans and stars never go into yours.

## Narration

Every demo is narrated by an **original** voice: confident, upbeat, and made of short lines.
Never imitate a real person's voice or catchphrases, even when asked. Offer the original
narrator instead.

- **Web scripts** use `startWebDemo(outDir, LINES)` from `demo/lib/web.mjs`, with every line
  listed up front, then `await demo.say(LINES.x)` at each step. `say` waits until the line
  has finished, so there are no pauses to keep in sync.
- **Tapes** put `# say: <line>` before the steps it describes. `Sleep` must be at least as
  long as the line. `record.sh` fails and names the line when it isn't.
- **Lines** should be one idea each, at most about 20 words (6 s). Say what the viewer is
  seeing right now, not what comes next.
- **Voice:** Kokoro `af_heart` by default. Set `DEMO_VOICE=am_michael` for a male narrator.
  `demo/setup-voice.sh` installs Kokoro once, and `record.sh` runs it if needed.
  `DEMO_TTS=say` is the robotic fallback.

## Steps

1. **Pick a name** for the feature, e.g. `multi-repo`. Copy the closest existing
   `demo/<name>/` as a starting point.
2. **Write the script as a story** of 3 to 6 steps, each ending on a result worth reading.
   Pause about 1.5 s on each result (`page.waitForTimeout(1500)` or `Sleep 2s`). Type with
   a visible delay (`pressSequentially(text, { delay: 60 })`, or VHS `TypingSpeed 60ms`).
   Use real repositories: JetBrains/MPS, JetBrains/koog, JetBrains/android and
   anthropics/skills all show the interesting cases.
3. **Record** with `demo/record.sh <name>`.
4. **Watch the result before you publish it.** Pull one frame per narrated line (the times
   are in `build/demo/<name>/*.narration.json`) with
   `ffmpeg -ss <seconds> -i build/demo/<name>/web.mp4 -frames:v 1 /tmp/frame.png`, and look at
   each one. The caption must describe what's on screen in that frame. Every step should show what the script says it shows. An empty list or a
   spinner in a key frame means the demo is wrong. Fix the script and record again.
5. **Open the PR first,** because you need its number. Then run
   `demo/publish.sh <pr-number> <name>` and paste the printed Markdown into the PR's
   **Demo** section (`gh pr edit <n> --body-file …`).
6. **Commit the scripts** (`demo/<name>/…`) in the PR, so reviewers can record the demo
   again. Never commit the recordings to the feature branch.

## Cases

**A web view change** (for example, several repositories with search):
- In `web.mjs`, open the URL. Fill `#url`, click `#scan-button`, and wait for
  `body[data-state=idle]` (use a 180 s timeout for large repositories). Type into
  `#filter`, click `[role=option]`, and scroll to `#similar`.
- Record at a 1280×800 viewport with `recordVideo: { dir, size }`. Then close the context
  and rename `await page.video().path()` to the output path.
- Keep it under 30 s. `record.sh` scales the GIF to 960 px at 10 fps.

**A plain or rich terminal change** (`scan`, `--filter`, `--skill`):
- In `terminal.tape`, use `Require skill-atlas`, then `Set Width 1400`, `Set Height 820`,
  and `Set FontSize 16`.
- Put setup such as `clear` between `Hide` and `Show`. Give each command a `Sleep` long
  enough for its scan to finish: about 9 s for two real repositories.
- The VHS terminal is a TTY, so `scan` shows the rich Mosaic view. Pipe into
  `sed -n 'a,bp'` to show the plain format, or to crop long output.

**A narrated feature tour:** see `demo/tour-*/`, and publish several demos at once with
`demo/publish.sh tour tour-web-basics tour-cli …`.

**An interactive terminal view** (`browse` or the shell):
- Send keys with VHS: `Type "/pdf"`, `Enter`, `Tab`, `Down`, `Escape`. Put a `Sleep 1s` after
  each key so viewers can follow along.
- End with the quit key (`Type "q"`), so the recording shows the terminal restored.

**Recording again after review feedback:**
1. Change the script.
2. Run `demo/record.sh <name>` again.
3. Run `demo/publish.sh <same-pr> <name>` again. It replaces `demos/pr-<n>/` in a new commit
   on `demos`, and the links in the PR stay the same.
4. GitHub caches images. If the old GIF still shows, add `&v=2` to the image URL in the PR.

**When something goes wrong:**
- **`GitHub API rate limit exceeded` during a demo:** `gh auth token` is missing or expired.
  Run `gh auth status`.
- **The recording shows old UI:** `record.sh` always runs `installDist`. If you started
  `serve` by hand, it's a stale build (see `memory/gotchas.md`).
- **The GIF is over its limit** (10 MB for web, 6 MB for terminal): shorten the steps.
  Don't lower the quality.
- **Captions lag the terminal video:** the tape needs `Set Framerate 20`. At 50 fps VHS drops
  frames and the video comes out time-compressed.
- **The caption is a huge dark box:** the caption page needs `align-items: flex-start`,
  otherwise flex layout stretches the bar to the full page height.
- **`publish.sh` fails on commit:** commit signing uses 1Password. Unlock it and run the
  script again, since it's safe to repeat. Pushing needs the `gh` credential helper set up
  as in `memory/gotchas.md`.
- **Images don't show in the PR:** the repository is private, so the `?raw=true` links work
  only for people who are signed in and have access. That's the same audience as the PR.

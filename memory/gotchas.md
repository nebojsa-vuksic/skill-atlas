# Gotchas

## The SSH key belongs to another GitHub account (2026-09-30)

`git@github.com:` authenticates as `vuksa`, not `nebojsa-vuksic`, so SSH clones of this
repo fail with "Repository not found".

**Why:** the first clone failed that way.
**How to apply:** the remote is HTTPS, and credentials come from `gh`. Keep the repo-local
config: `credential.helper = ""`, then `credential.helper = !gh auth git-credential`.

## The system keychain helper returns a stale token (2026-09-30)

`/opt/homebrew/etc/gitconfig` sets `osxkeychain`, which git asks before the repo's `gh`
helper.

**Why:** pushing `.github/workflows/ci.yml` was rejected for a missing `workflow` scope,
even after `gh auth refresh -s workflow`.
**How to apply:** the empty `credential.helper = ""` entry in `.git/config` resets the helper
list. Pushing workflow files needs the `workflow` scope on the `gh` token.

## Commit signing goes through 1Password (2026-09-30)

When 1Password is locked, `git commit` fails with "1Password: failed to fill whole buffer",
and a merge is left staged.

**How to apply:** retry once 1Password is unlocked. Never turn signing off to get past it.

## The anonymous GitHub API limit is 60 requests an hour (2026-09-30)

A scan without `GITHUB_TOKEN` fails with exit code `5` once other tools on the network
have used up the limit.

**How to apply:** run scans with `GITHUB_TOKEN=$(gh auth token)`.

## The Compose compiler plugin runs on every source set (2026-09-30)

`compileIntegrationTestKotlin` failed with "The Compose Compiler requires the Compose
Runtime to be on the class path".

**How to apply:** each new source set needs `compileOnly(libs.mosaic.runtime)`.

## Mosaic needs Google's Maven repository and an explicit mosaic-tty (2026-09-30)

Its Compose runtime pulls AndroidX artifacts that are only published on `google()`. `Tty`
(terminal detection) comes from `mosaic-tty`, which is only a runtime dependency unless you
declare it.

**How to apply:** keep `google()` in `repositories`, and declare `libs.mosaic.tty`.

## Mosaic only reports printable ASCII as keys (2026-09-30)

`toKeyEventOrNull` maps codepoints 32 to 126 to characters, plus named keys such as
`ArrowUp`, `Enter`, `Escape`, `Backspace`, `Tab`, `PageUp`, `Home` and `F1`. Other
codepoints throw `UnsupportedOperationException`, which ends the whole composition.
Ctrl-C arrives as key `c` with `ctrl`, Ctrl-D as `d`; an unhandled Ctrl-C cancels the program.

**Why:** typing `é` in the first shell killed it. Restarting `runMosaicBlocking` then failed
with "Tty already bound": Mosaic never frees the `Tty` it binds, so one process gets one
Mosaic session.
**How to apply:** the `browse` filter accepts ASCII only (spec 5.8). The shell binds the
terminal itself (`ShellTerminal.kt`: `Tty.tryBind()`, `asTerminalIn`, the public `Mosaic(...)`
and a copy of the ANSI rendering) and diverts unnamed keys before Mosaic sees them. Reuse
that for any other long-lived view that must accept free text.

## A Mosaic composition ends when no effect is running (2026-09-30)

`runMosaic` returns as soon as every `LaunchedEffect` is done, even while UI is still on
screen. Plain variables read in composition don't trigger recomposition.

**Why:** in the first `browse`, the error path used a plain var and hung on the spinner.
**How to apply:** interactive views need an effect that waits for quit, like
`snapshotFlow { quit }.first { it }`. Every flag that changes what's shown must be
`mutableStateOf`.

## Kotlin `+=` is ambiguous on a list of lists (2026-09-30)

With `MutableList<List<Span>>`, `lines += listOf(...)` fails to compile. It could mean
either add or addAll.

**How to apply:** use `lines.add(...)` or `lines.addAll(...)` explicitly.

## Timing tests run on a cold JVM on 2-core CI runners (2026-09-30)

The 500-skill similarity test took 0.4 s locally but 1.23 s on Ubuntu CI.

**How to apply:** leave a wide margin, and optimize the code rather than the limit.

## zsh expands `===` and ugrep hides NUL files (2026-09-30)

`echo ===` fails in zsh with "== not found". `grep`, which is `ugrep` here, prints nothing
for a file that contains a NUL byte.

**How to apply:** avoid `=`-leading words in echo, and use `grep -a` when a file might
contain binary.

## A stale installDist looks like a missing feature (2026-09-30)

`build/install/skill-atlas` in the main checkout is only as new as its last `installDist`.
Worktrees build their own copy.

**Why:** the owner didn't see the filter or similar skills because they were running a
12:43 build.
**How to apply:** after merging, run `./gradlew installDist` before trying things. Stop old
servers first (`kill $(lsof -t -iTCP:8421 -sTCP:LISTEN)`).

## sbx setup on this machine (2026-09-30)

- **Install:** `brew tap docker/tap && brew install --cask docker/tap/sbx`. This is the sbx
  CLI; Docker Desktop is not needed.
- **Login:** `sbx login` is interactive, so the owner does it.
- **Daemon:** start it with `sbx daemon start`.
- **Network policy:** `balanced`, plus three allow rules: `api.foojay.io` and
  `api.adoptium.net` (so Gradle can download a JDK) and `localhost:19516` (the JetBrains
  Central proxy on the host).
- **Secrets:** the GitHub token is stored once with
  `sbx secret set github --command "gh auth token"`.

**How to apply:** without the `localhost:19516` rule, Claude in the sandbox fails with
"403 Blocked by network policy".

## Inside the sandbox (2026-09-30)

- **Java:** only Java 25 is installed, and `JAVA_TOOL_OPTIONS` routes Java traffic through
  a proxy. Our tests clear the environment for the CLI process, so this doesn't leak into
  exact stderr checks.
- **Chromium:** it needs system libraries. Install them with
  `./gradlew installPlaywrightChromium -PplaywrightWithDeps`.

## Headless `claude -p` can stop early (2026-09-30)

A non-interactive run ended "successfully" with an empty result when a turn contained only
thinking.

**How to apply:** check `git status` and whether a PR exists, not just the exit code. Resume
with `claude -p --resume <session-id>`. The session ID is in the stream-json log.

## macOS runs bash 3.2 for `#!/usr/bin/env bash` scripts (2026-09-30)

Bash 4 features, such as `${var^}`, `mapfile`, and associative arrays, fail in `demo/*.sh`.

**How to apply:** keep scripts to bash 3.2, and use `awk` or `tr` for case changes.
`stat -f %z` is macOS and `stat -c %s` is Linux. Try `stat -c %s` first: GNU `stat -f` means
"filesystem status" and succeeds with the wrong output, which broke `record.sh` on Linux.

## An exact JSON test must change when the page switches APIs (2026-09-30)

`WebViewIntegrationTest` asserts that `app.js` calls a specific endpoint. Moving the page
from `/api/scan` to `/api/scans` broke it, as intended.

**How to apply:** when a test fails because behavior changed on purpose, update the
assertion and say why in its message. Only do this when the spec changed too.

## Subagent worktrees live inside the repository (2026-09-30)

Claude Code creates subagent worktrees under `.claude/worktrees/<agent>/`. A plain
`git add -A` then stages them as embedded repositories (gitlinks).

**Why:** the multi-repo commit picked one up and pushed it. A follow-up commit removed it.
**How to apply:** `.claude/worktrees/` is in `.gitignore` now. Check `git status --short`
before committing when a subagent is running. Never delete that directory while its agent
is working.

## `rememberCoroutineScope` keeps a Mosaic program running forever (2026-09-30)

`runMosaic` waits for every child of the effect job, and a `rememberCoroutineScope()` scope
is one whose `Job` never completes. The first shell hung after `/quit`.

**How to apply:** run background work in a `LaunchedEffect` keyed on the state that
starts it (the shell's `ShellMode.Scanning`); leaving that state cancels it.

## Printing scrollback from a Mosaic REPL (2026-09-30)

Output meant to stay in the terminal goes through `StaticEffect`, which renders once into
Mosaic's static log. For a stream of outputs, keep a snapshot list of pending blocks,
compose `key(id) { StaticEffect { … } }` for each, and remove them in a `SideEffect`
after the loop (`ShellApp` in `ShellView.kt`). Only the prompt stays live.

**How to apply:** test it with `runMosaicTest(MosaicSnapshots)`, then `static()` on the
`TestMosaic`; it returns the printed text with `\r\n` line ends and ANSI codes.

## Key events go to children first in Mosaic (2026-09-30)

`onKeyEvent` handlers run child-first, and a handler returning `true` stops the event.
A parent can't see a key its child handled.

**How to apply:** the shell composes `Browser` inside itself and waits for `state.quit`
with `snapshotFlow` rather than in its own key handler.

## The worktree sandbox refuses some shell commands (2026-09-30)

Agents isolated in a worktree get "too complex to verify" or "cannot be shown not to be
git" refusals for heredocs, `export X=$(…)`, or loops with variables, and for any inline
script whose text contains `git`.

**How to apply:** write scripts to `/tmp` with the Write tool and run them with `python3` or
`bash`, one plain command per call.

## VHS at 50 fps drops frames and time-compresses the video (2026-09-30)

On this laptop, VHS's default framerate produced videos 25–40 % shorter than the tape, so
the narration drifted behind the screen. At `Set Framerate 20`, a 29.5 s tape records as
29.48 s.

**How to apply:** every tape sets `Framerate 20`. `finish.mjs` also stretches a recording
that comes out more than 3 % short of `terminal.duration`.

## Homebrew ffmpeg has no drawtext or subtitles filter (2026-09-30)

The build lacks libass and freetype, so captions can't be drawn by ffmpeg.

**How to apply:** captions are rendered as PNGs with headless Chromium and laid over the
video with `overlay=…:enable='between(t,a,b)'` (`demo/lib/finish.mjs`).

## A flex parent stretches its child to the viewport height (2026-09-30)

The first caption PNGs were 400 px tall dark boxes covering the video.

**How to apply:** use `align-items: flex-start` when screenshotting a single element for
compositing.

## kokoro-onnx works on Python 3.14 without PyTorch (2026-09-30)

`pip install kokoro-onnx soundfile` works on Homebrew's Python 3.14. The model files
(`kokoro-v1.0.onnx`, 310 MB, and `voices-v1.0.bin`, 27 MB) come from the kokoro-onnx GitHub
release. Each line takes about 1.3 s to render once the model is loaded, so load it once
per demo (`kokoro_say.py` reads JSON on stdin).

## VHS Wait+Screen reads the top of the buffer, and Screenshot is async (2026-09-30)

With a program that prints scrollback (the shell), `Wait+Screen /x/` timed out on text
near the bottom. Its error showed the first buffer line ("last value was: $ skill-atlas").
`Wait` (line mode) saw an empty line. A `Screenshot` followed at once by `Type` captured
the next keystroke.

**How to apply:** `clear` before commands whose output you wait on. After in-process keys,
use a short `Sleep`. Put `Sleep 1s` after every `Screenshot`. The shell palette shows at
most 8 rows, so `/help` isn't visible after typing `/`.

## Unquoted YAML descriptions containing ": " become invalid frontmatter (2026-09-30)

"Use when defining generators: templates, …" parsed as a new key, and the skill showed
`⚠ invalid frontmatter`. The parser was right, and the demo fixtures were wrong.

**How to apply:** quote descriptions in fixtures, as `DemoFixtures.skill()` does.

## GitHub Actions pushes with GITHUB_TOKEN don't start workflows (2026-09-30)

The Update screenshots workflow commits baselines and then must start CI itself.

**How to apply:** use `gh workflow run ci.yml --ref <branch>`, which needs
`permissions: actions: write`. `ci.yml` has `workflow_dispatch` for this.

## "No space left on device" from ffmpeg 6.x is not about disk (2026-10-01)

On Ubuntu's ffmpeg 6.1, `amix,apad` with `-shortest` failed with `Error while filtering: No
space left on device` while 30 GB were free. ffmpeg 9 on the Mac was fine. We first freed
disk space, which didn't help.

**How to apply:** pad audio to an explicit length (`apad=whole_dur=<s>`) and cut with
`-t <s>`, never `-shortest`. Check `df -h` before blaming the disk.

## The JetBrains Air Linux sandbox blocks most downloads (2026-10-01)

Only Java 25 is installed; foojay, `dl.google.com` (Mosaic's AndroidX) and Playwright's CDN
return "403 Blocked by network policy", and there's no sudo. What worked, all outside the repo:
- Temurin 21 from its GitHub release into `~/.local/jdks`, named in `~/.gradle/gradle.properties`
  as `org.gradle.java.installations.paths`.
- `~/.gradle/init.d/google-mirror.gradle` points `dl.google.com` repositories at
  `https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2/`.
- Chrome headless shell from `storage.googleapis.com/chrome-for-testing-public/<version>/`
  into `~/.cache/ms-playwright/chromium_headless_shell-<rev>/` with an `INSTALLATION_COMPLETE`
  file, and BtbN's static FFmpeg from GitHub as `ffmpeg-<rev>/ffmpeg-linux`. Without that
  FFmpeg, run `./gradlew build -x installPlaywrightChromium` with
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`; every test still runs.
- Chromium's libraries and fonts via `apt-get download` with `-o Dir::State=/tmp/apt/state`,
  unpacked with `dpkg -x` into `~/.local/chromium-libs`; run Gradle with `LD_LIBRARY_PATH` and
  `FONTCONFIG_FILE` set.

**Why:** without fonts, 12 browser tests failed on an untouched `main` with zero-height text,
or Chromium crashed with `TargetClosedError`.
**How to apply:** if browser tests fail with `height=0` or timeouts, check fonts before code.
VHS and Kokoro aren't available there, so demos are recorded on the owner's machine. `gh`
only works with the working directory inside a git checkout.

## GitHub lists only a user's public repositories (2026-10-01)

`GET /users/{user}/repos` never returns private repositories, even for the token's own
account; `/orgs/{org}/repos?type=all` does include the private ones the token can see.

**How to apply:** an owner scan of your own user account misses your private repositories.
`/user/repos` would cover them; it isn't used yet.

## The 500-skill similarity timing test fails under IDE load (2026-10-01)

It took 1.31 s once in a full `./gradlew test` while the IDE's analyzer used about two cores,
and passed three times in a row alone.

**How to apply:** check `uptime` and rerun on a quiet machine; never raise the limit.


## "The job was not started because recent account payments have failed" (2026-10-01)

Every Actions job on every branch, `main` included, failed with no steps. The check-run
annotation said: "The job was not started because recent account payments have failed or
your spending limit needs to be increased." It was billing, not code. Before that, every PR
ran CI twice (`push` plus `pull_request`), with macOS at ten times the Linux rate and a
12-minute Demos job.

**How to apply:** when jobs fail with no steps, read the annotations
(`gh api repos/<repo>/check-runs/<id>/annotations`) before debugging. Only the owner can fix
billing. CI now runs `push` for `main` only, macOS only on `main`, and cancels a PR's older
in-progress run.

## The Demos runner is pinned to ubuntu-24.04 (2026-10-01)

`ubuntu-latest` moves to Ubuntu 26 on 2026-10-19 (annotation on every run). A new image's
fonts and libraries would change every screenshot at once.

**How to apply:** the Demos job and Update screenshots use `ubuntu-24.04`. Moving to a new
image is a separate PR: change both `runs-on` lines, run Update screenshots, and review the
diffs.

## Terminal demos used a fallback font on CI (2026-10-01)

Every terminal baseline had widely spaced letters: JetBrains Mono was installed in
`~/.local/share/fonts`, but the demo sandbox sets `XDG_DATA_HOME` to a temporary directory, so
fontconfig never looked there. The baselines matched anyway, because the fallback was
deterministic.

**How to apply:** demo fonts go in `/usr/local/share/fonts`, and demo setup fails if
`fc-match` can't find them with `XDG_DATA_HOME` moved. A screenshot can match its baseline and
still be wrong, so look at new baselines before accepting them.

## Update screenshots commits wait for approval on the PR (2026-10-01)

Its commit is pushed by `github-actions[bot]`, so the `pull_request` run on it stops as
`action_required`. The `workflow_dispatch` run it starts runs normally.

**How to apply:** after reviewing the new baselines, approve the held run with
`gh api -X POST repos/<repo>/actions/runs/<id>/approve`. The DoD needs that `pull_request` run
green.

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
codepoints throw.

**How to apply:** the `browse` filter accepts ASCII only. This is documented in spec 5.8.

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
`stat -f %z` is macOS and `stat -c %s` is Linux, so the demo scripts try both.

## An exact JSON test must change when the page switches APIs (2026-09-30)

`WebViewIntegrationTest` asserts that `app.js` calls a specific endpoint. Moving the page
from `/api/scan` to `/api/scans` broke it, as intended.

**How to apply:** when a test fails because behavior changed on purpose, update the
assertion and say why in its message. Only do this when the spec changed too.

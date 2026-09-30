# Skill Atlas

Lists the agent skills (`SKILL.md` files) defined in a GitHub repository, together with
the repository's name, description, and the commit that was scanned. See
[`specs/spec.md`](specs/spec.md) for the full specification.

```
skill-atlas scan <github-project-url>
skill-atlas serve [--port <port>]
```

## Requirements

- Java 21 or newer
- `git` on `PATH`
- Optional: `GITHUB_TOKEN` for private repositories and a higher GitHub API rate limit

## Build and run

```
./gradlew installDist
build/install/skill-atlas/bin/skill-atlas scan https://github.com/anthropics/skills
```

In a terminal the results are shown in a color-highlighted view built with
[Mosaic](https://github.com/JakeWharton/mosaic), with a live status line while scanning.
When stdout is piped or redirected, the tool prints plain text instead.

### Filter, skill details and interactive browsing

```
skill-atlas scan <url> --filter "test run"     # only skills whose name or description has every word
skill-atlas scan <url> --skill mps-tests       # one skill: description, paths, similar skills, SKILL.md
skill-atlas browse <url>                       # full-screen list + details in the terminal
```

In `browse`: ↑/↓ select, `/` filter, Tab to jump into similar skills, PgUp/PgDn scroll,
Esc clear the filter, `q` quit.

### Web view

```
build/install/skill-atlas/bin/skill-atlas serve            # http://127.0.0.1:8421/
build/install/skill-atlas/bin/skill-atlas serve --port 0   # any free port
```

This serves a local page on top of the same scanner. Skills are listed on the left;
click one, or use ↑/↓, to see its full description and `SKILL.md` content on the right.
Drag the divider to resize the panes. Open the printed URL, or link
straight to a scan with `http://127.0.0.1:8421/?url=https://github.com/anthropics/skills`.
The JSON API is `GET /api/scan?url=<repository-url>`.

`./gradlew distZip` builds a distributable archive in `build/distributions/`.

Every successful scan is appended as one JSON line to
`$XDG_STATE_HOME/skill-atlas/scans.log` (default `~/.local/state/skill-atlas/scans.log`).

## Tests

```
./gradlew build
```

This runs the unit tests (`./gradlew test`) and the CLI integration tests
(`./gradlew integrationTest`). The integration tests run the installed `skill-atlas`
launcher as a separate process against local fixture repositories and a stub GitHub API,
including one run inside a pseudo-terminal, and drive the web view in headless Chromium
with Playwright. They need `git` and `python3`. The first run downloads Chromium once
(`./gradlew installPlaywrightChromium`); after that, no network access is needed.

CI runs the same build on Linux and macOS for every push and pull request. See the
definition of done in [`specs/spec.md`](specs/spec.md#12-definition-of-done): every
change goes through a pull request and is merged once CI is green.

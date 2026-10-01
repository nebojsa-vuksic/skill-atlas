# Skill Atlas

Lists the agent skills (`SKILL.md` files) defined in a GitHub repository, together with
the repository's name, description, and the commit that was scanned. See
[`specs/spec.md`](specs/spec.md) for the full specification.

```
skill-atlas                              # in a terminal: the interactive shell
skill-atlas scan <github-project-url>
skill-atlas serve [--port <port>]
```

## Demos

A narrated tour of every feature, recorded from scripts in `demo/tour-*/` (spec section
13.5). The videos have a voice-over and the GIFs have captions. They live on the `demos`
branch, so viewing them needs access to this private repository.

| Video | Shows | |
|-------|-------|-|
| [tour-web-basics](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-web-basics/web.mp4) | Scanning a repository, the split pane, the Raw tab, the divider, similar skills | [GIF](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-web-basics/web.gif?raw=true) |
| [tour-web-search](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-web-search/web.mp4) | JetBrains/MPS duplicates and product skills, the filter, snippets, Esc, the URL state | [GIF](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-web-search/web.gif?raw=true) |
| [tour-web-multi](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-web-multi/web.mp4) | Three repositories, grouped lists, `repo:` search, similar skills across repositories | [GIF](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-web-multi/web.gif?raw=true) |
| [tour-cli](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-cli/terminal.mp4) | `scan` rich and plain, merged copies, fixtures, `--filter`, `--skill`, several repositories | [GIF](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-cli/terminal.gif?raw=true) |
| [tour-browse](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-browse/terminal.mp4) | `browse`: moving, the live filter, similar skills, quitting | [GIF](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-browse/terminal.gif?raw=true) |
| [tour-shell](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-shell/terminal.mp4) | The shell: the palette, Tab completion, `/scan`, `/filter`, `/skill`, `/log` | [GIF](https://github.com/nebojsa-vuksic/skill-atlas/blob/demos/demos/tour/tour-shell/terminal.gif?raw=true) |

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

### Several repositories

```
skill-atlas scan github.com/JetBrains/MPS github.com/JetBrains/koog --filter "repo:mps test"
```

`repo:<text>` narrows a search to repositories whose name contains `<text>`. The web view
takes several URLs at once, groups the skills by repository, and finds similar skills
across them.

### Whole organizations

```
GITHUB_TOKEN=$(gh auth token) skill-atlas scan https://github.com/JetBrains
```

An organization or user URL scans every repository of that owner that has skills. Forks
and archived repositories are skipped, and each repository's file tree is checked through
the GitHub API first, so only repositories with a `SKILL.md` are cloned. The report ends
with a `Searched <owner>: <k> of <n> repositories have skills` line. Owner URLs mix with
repository URLs, and the web view shows an owner as one chip. It makes about one API
request per repository, so set `GITHUB_TOKEN`. See spec section 5.11.

### Demos for pull requests

`demo/record.sh <name>` records the scripted demo in `demo/<name>/` (Playwright for the web
view, VHS for the terminal), with a local neural voice-over (Kokoro) and captions, into
`build/demo/<name>/`. `demo/publish.sh <pr> <name>` pushes
it to the `demos` branch and prints the Markdown for the PR. See spec section 13.

### Filter, skill details and interactive browsing

```
skill-atlas scan <url> --filter "test run"     # only skills whose name or description has every word
skill-atlas scan <url> --skill mps-tests       # one skill: description, paths, similar skills, SKILL.md
skill-atlas browse <url>                       # full-screen list + details in the terminal
```

In `browse`: ↑/↓ select, `/` filter, Tab to jump into similar skills, PgUp/PgDn scroll,
Esc clear the filter, `q` quit.

### Interactive shell

```
skill-atlas            # or: skill-atlas shell
```

A prompt with slash commands, in the style of the Claude Code CLI. Type `/` for the command
palette: it filters as you type, ↑/↓ select, Tab completes, Enter runs, Esc closes.

```
/scan https://github.com/anthropics/skills    # scan, and keep it as the current repository
/filter pdf                                   # like scan --filter (or just type: pdf)
/skill docx                                   # like scan --skill; Tab after "/skill " completes names
/similar docx                                 # only the similar-skills table
/browse                                       # the full-screen browse view; q comes back
/repo  /log  /serve [port]  /serve stop  /help  /quit
```

↑/↓ on the prompt walk the history. Ctrl-C clears the input, cancels a running scan, or quits
on an empty input; Ctrl-D quits too.

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

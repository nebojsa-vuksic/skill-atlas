# Skill Atlas — Specification

## 1. Overview

Skill Atlas is a command-line tool that scans a GitHub repository and reports every
agent skill defined in it. For each skill it lists the skill's **name** and
**description**. Each scan also logs which repository was scanned: its **name**,
**description**, and the **commit** that was read. In a terminal, the results are
shown in a rich, color-highlighted view built with Mosaic (section 5.1).

```
skill-atlas scan <github-project-url>
```

## 2. Definitions

| Term | Meaning |
|------|---------|
| **Skill** | A directory containing a `SKILL.md` file. The file starts with YAML frontmatter that declares the skill's `name` and `description` (the Agent Skills format used by Claude Code and similar tools). |
| **Skill file** | The `SKILL.md` file itself. |
| **Frontmatter** | The YAML block at the top of a skill file, between the opening `---` line and the next `---` line. |
| **Scanned commit** | The full 40-character SHA of the commit whose files were read. |


## 3. CLI interface

### 3.1 Synopsis

```
skill-atlas scan <github-project-url>
skill-atlas serve [--port <port>]
skill-atlas --help
skill-atlas --version
```

### 3.2 Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `<github-project-url>` | yes | URL of the GitHub repository to scan. See 3.3 for accepted forms. |

### 3.3 Accepted URL forms

Skill Atlas must accept all of the following and normalize each one to `owner/repo`:

- `https://github.com/owner/repo`
- `https://github.com/owner/repo/` (trailing slash)
- `https://github.com/owner/repo.git`
- `http://github.com/owner/repo` (upgraded to https)
- `github.com/owner/repo` (no scheme)
- `git@github.com:owner/repo.git` (SSH form)

Anything else, such as a non-GitHub host or a URL with no repo segment, is rejected
with exit code `2` (see section 7).

### 3.4 Options

| Option | Default | Description |
|--------|---------|-------------|
| `-h, --help` | | Print usage and exit `0`. |
| `-V, --version` | | Print the version and exit `0`. |
| `-p, --port <port>` | `8421` | `serve` only. Port for the web view. `0` picks a free port. |

## 4. Behavior

### 4.1 Processing steps

1. **Parse and validate the URL.** Normalize it to `owner/repo`.
2. **Fetch repository metadata** from the GitHub REST API (`GET /repos/{owner}/{repo}`):
   - `full_name` → repository name
   - `description` → repository description (may be `null`)
   - `default_branch` → the branch that is scanned
3. **Get the repository contents** of the default branch into a temporary directory.
   Only `SKILL.md` files are needed, so the clone is shallow and partial
   (`git clone --depth 1 --branch <default-branch> --filter=blob:none --no-checkout`).
   A sparse checkout (`git sparse-checkout set --no-cone SKILL.md`, then
   `git checkout`) then downloads just the skill files. For large repositories this is
   many times faster than a full checkout: `JetBrains/kotlin` drops from about 40 s to
   about 4 s. If a server doesn't support partial clones, git sends the full contents,
   and the result is the same.
   Scans always use the default branch; there is no option to choose another one.
4. **Record the scanned commit** with `git rev-parse HEAD`.
5. **Discover skills** (section 4.2).
6. **Parse each skill file** (section 4.3).
7. **Print the report** (section 5).
8. **Remove the temporary directory**, including when an earlier step failed.

### 4.2 Skill discovery

- Walk the whole repository tree recursively.
- A file is a skill file when its name is exactly `SKILL.md`. The match is
  case-sensitive, so `skill.md` does not count.
- Skip these directories: `.git`, `node_modules`, `vendor`, `dist`, `build`.
- Do not follow symbolic links. This prevents loops and reading files outside the
  repository.
- Sort results by the skill file's path relative to the repository root, so output
  is deterministic.
- Do not do lookup outside of the repo directory

### 4.3 Skill parsing

For each skill file:

- Read it as UTF-8. The frontmatter must be the very first thing in the file
  (a leading BOM is allowed).
- Parse the frontmatter as YAML.
- `name`: the string value of the `name` key. If it is missing or empty, use the name
  of the directory that contains the file and add the warning `missing name`.
- `description`: the string value of the `description` key, with surrounding
  whitespace trimmed. Multi-line YAML strings (`|`, `>`) are supported and kept as-is.
  If it is missing or empty, use an empty string and add the warning
  `missing description`.
- Other frontmatter keys are ignored.
- If the file has no frontmatter, or the YAML is invalid, still list the skill,
  using the directory name as its name, and add the warning `invalid frontmatter`.
  One broken skill must not stop the scan.
- Files larger than 1 MB are skipped with the warning `file too large`.

Each skill result contains:

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Skill name |
| `description` | string | Skill description |
| `path` | string | Path of the skill's directory relative to the repository root (`.` for the root) |
| `warnings` | string[] | Problems found while parsing; empty when none |

### 4.4 Duplicates, product skills, and test data

Real repositories keep skill files in more places than one skills folder. After parsing,
each skill file is classified by the folders **above** its skill directory. The skill's
own directory name never counts, so a skill called `tests` is still a skill.

| Location | Rule | Example | Result |
|----------|------|---------|--------|
| Test data | A parent folder is `test`, `tests`, `testdata`, `test-data`, `fixtures`, `__fixtures__` or `__tests__` (case-insensitive), or a folder directly under `src` whose name contains `test` (`src/test`, `src/jvmTest`, `src/integrationTest`, `src/testFixtures`) | JetBrains/koog `integration-tests/src/jvmTest/resources/skills/weather-retrieval` | **Not a skill.** Listed separately as an ignored test fixture |
| Product | Otherwise, a parent folder is `resources` | JetBrains/MPS `plugins/mcp-tools/resources/…/skills/mps-aspect-generator` | Listed, labeled **shipped in product** |
| Agent configuration | Otherwise, the first folder starts with `.` | `.agents/skills/…`, `.claude/skills/…` | Listed |
| Repository | Anything else | JetBrains/android `agent/skills/writing-lint-checks` | Listed. An unusual folder is still the repository's own skill |

**Identical copies are one skill.** Skill files with byte-for-byte identical content
(same SHA-256) are merged into a single entry. For example, JetBrains/MPS
`.agents/skills/mps-tests/SKILL.md` and `.claude/skills/mps-tests/SKILL.md` become one
`mps-tests` entry.

- **Main path:** the copy with the highest-priority location, in the order agent
  configuration, repository, product. Ties go to the alphabetically first path, so
  `.agents/…` wins over `.claude/…`.
- **Other copies:** listed as `also in <path>`, in that same order.
- **Product label:** the entry is labeled *shipped in product* if any copy is a product
  location.
- **Not merged:** files over 1 MB or unreadable files are never merged.
- **Test data first:** test data is removed before merging, so a fixture never
  becomes a copy of a real skill.

**Same name, different content.** If two different skills end up with the same `name`,
both are listed, and each gets the warning `duplicate name`.

Real-world outcome: JetBrains/MPS has 114 `SKILL.md` files. They are reported as
**41 skills**: every skill has an identical `.claude` copy, and 32 of them are also
shipped in `plugins/mcp-tools/resources`.

## 5. Output

The report goes to **stdout**. Errors and warnings go to **stderr**.

How the report looks depends on where stdout goes:

- **stdout is a terminal:** the rich terminal view (section 5.1).
- **stdout is a pipe or a file:** the plain text format (section 5.2). This keeps the
  output free of escape codes so it can be piped or redirected cleanly.

### 5.1 Rich terminal view

The rich view is rendered with [Mosaic](https://github.com/JakeWharton/mosaic), a
Compose-based terminal UI library for Kotlin.

**While scanning,** one live status line shows a spinner and the current step, e.g.
`⠹ Cloning anthropics/skills (main)…`. It replaces the progress messages that the plain
format prints to stderr. The status line is removed when the scan ends.

**When the scan finishes,** the report is printed once and stays in the terminal's
scrollback:

```
 SKILL ATLAS

 Repository   anthropics/skills
 Description  Public repository for Agent Skills
 Commit       3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39  main

 3 skills found

 ● pdf-extract
   Extract text and tables from PDF files. Use when the user asks to read or parse a PDF.
   skills/pdf-extract

 ● brand-guidelines
   Apply company brand colors and typography to documents.
   skills/brand-guidelines

 ● csv-tools  ⚠ missing description
   (no description)
   skills/csv-tools
```

Each part of the report is highlighted:

| Part | Style |
|------|-------|
| `SKILL ATLAS` title | bold, inverted |
| Labels (`Repository`, `Description`, `Commit`) | dim |
| Repository name | bold cyan |
| Repository description | italic; `(none)` in dim when missing |
| Commit hash | bold yellow |
| Branch name | magenta |
| Skill count line | bold green; `No skills found.` in yellow |
| Skill name and its `●` bullet | bold cyan |
| Skill description | default color; `(no description)` in dim |
| Skill path | dim |
| Warnings (`⚠ …`) | yellow |
| `◆ shipped in product` | magenta |
| `also in <path>` lines | dim |
| Ignored test fixtures heading and paths | dim |

If the terminal does not support color, Mosaic falls back to plain text with the same
layout.

### 5.2 Plain text format

```
Repository:  anthropics/skills
Description: Public repository for Agent Skills
Commit:      3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39 (main)

Found 3 skills:

  pdf-extract
    Extract text and tables from PDF files. Use when the user asks to read or parse a PDF.
    skills/pdf-extract

  brand-guidelines
    Apply company brand colors and typography to documents.
    skills/brand-guidelines

  csv-tools  [warning: missing description]
    (no description)
    skills/csv-tools
```

With the cases from section 4.4 (identical copies, a product copy, a test fixture):

```
  mps-tests  [shipped in product]
    Use when writing or modifying tests inside MPS models.
    .agents/skills/mps-tests
    also in .claude/skills/mps-tests
    also in plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-tests

Ignored 1 test fixture (not skills):
  integration-tests/src/jvmTest/resources/skills/weather-retrieval
```

Rules:
- `Description:` shows `(none)` when the repository has no description.
- `Commit:` shows the full SHA, followed by the default branch name in parentheses.
- When no skills are found, the header is followed by `No skills found.`
- Tags follow the skill name in this order: `[shipped in product]`, then `[warning: …]`.
- Each identical copy adds an `also in <path>` line under the main path.
- If test fixtures were ignored, the report ends with a blank line, then
  `Ignored <n> test fixture(s) (not skills):`, then one indented path per fixture. This
  also appears after `No skills found.`
- Progress messages (`Fetching metadata for owner/repo...`, `Cloning owner/repo (main)...`)
  go to stderr.

### 5.3 Shortened skill descriptions

Both formats show a shortened form of each skill description, so every skill takes
three lines:

- Line breaks and runs of whitespace are collapsed into single spaces.
- A description longer than **100 characters** is cut at the last word boundary
  before the limit, and `…` is appended.
- In the rich view, the limit is also reduced to fit the terminal width.

The scan log (section 6) does not store skill descriptions, so nothing is lost there.

### 5.4 Web view

`skill-atlas serve` starts a local web server with the same scan behavior as `scan`.
It uses the same URL parsing, GitHub metadata, clone, discovery, parsing, shortening,
and scan log. The server runs until it is stopped with Ctrl-C.

On start it prints this to stdout:

```
Skill Atlas web view: http://127.0.0.1:8421/
Press Ctrl-C to stop.
```

If the port is taken, it exits `1` with `error: could not listen on 127.0.0.1:<port>: <reason>`.

**Page.** `GET /` serves a single page with a repository URL field and a Scan button.
While a scan runs, the page shows a spinner and `Scanning <url>… <n>s`, with a counter
that ticks every second. After 10 seconds it adds `(large repositories can take a minute)`,
so a long clone never looks stuck. The result uses the
same highlights as the rich terminal view:

| Part | Style |
|------|-------|
| Repository name | bold, accent color, links to the repository on GitHub |
| Repository description | italic; `(none)` in a muted color |
| Commit hash | bold monospace, amber |
| Branch | pill with a magenta outline |
| Skill count | green; `No skills found.` in amber |
| Skill name | bold, accent color, with a `●` bullet |
| Skill description | the shortened form (section 5.3); the full text is shown on hover |
| Skill path | muted monospace |
| Warnings | amber `⚠ …` badges |
| Shipped in product | `◆ shipped in product` pill with a magenta outline |
| Identical copies | muted `also in <path>` lines |
| Ignored test fixtures | a muted list under the skills |

Errors are shown as `error: <message>`, using the same messages as section 7. Opening
`/?url=<repository-url>` starts a scan right away. The page follows the system's light
or dark color scheme and works at phone widths.

**Split pane.** Below the repository summary and the skill count, the result is a split
pane:

```
┌──────────────────────────┬─┬──────────────────────────────────────────────┐
│ ● commits                │ │ mps-aspect-generator  ◆ shipped in product   │
│   How to write commit…   │ │ Use when defining or modifying MPS generat…  │
│   .agents/skills/commits │ │ .agents/skills/mps-aspect-generator          │
├──────────────────────────┤ │ also in .claude/skills/mps-aspect-generator  │
│▌● mps-aspect-generator   │ │ View on GitHub ↗                              │
│▌  Use when defining or…  │ │ [ Rendered | Raw ]                            │
│▌  .agents/skills/mps-…   │ │ ─────────────────────────────────────────────│
├──────────────────────────┤ │ # MPS generators                              │
│ ● mps-tests              │ │ …the skill file's content…                    │
└──────────────────────────┴─┴──────────────────────────────────────────────┘
   skill list (left)       divider       selected skill (right)
```

- **Left, the skill list.** Each skill is one item, with the same content and styles as
  in the table above. Items are buttons. Clicking an item, or pressing Enter or Space,
  selects it. ↑ and ↓ move the selection. The selected item is highlighted with the
  accent color and marked `aria-selected="true"`. The list scrolls on its own.
- **Right, the selected skill.** This pane shows:
  - the skill name and its badges
  - the **full** description, not the shortened one
  - the main path and its `also in` copies
  - a **View on GitHub** link to
    `https://github.com/<repository>/blob/<commit>/<path>/SKILL.md`
  - the skill file's content, in two tabs:
    - **Rendered** (the default): the Markdown after the frontmatter, rendered as
      CommonMark with GitHub-style tables.
    - **Raw**: the exact file text, frontmatter included, in monospace.

  The pane scrolls on its own. If the content isn't available (warnings
  `file too large`, `unreadable file`, or a file that isn't UTF-8), it shows
  `(content not available)`.
- **Divider.** Drag it to resize the panes. When it's focused, ← and → move it. Each pane
  keeps at least 240 px. The left pane starts at 38 % of the width, and the chosen width
  is remembered in the browser.
- **Selection.** After a scan, the first skill is selected. The selected skill's path is
  kept in the page URL as `&skill=<path>`, so a link reopens the same repository with
  the same skill selected.
- **Narrow screens.** Below 760 px wide, the panes stack: the list comes first, then the
  selected skill. Selecting an item scrolls the skill into view.
- **Ignored test fixtures** stay listed below the split pane, and they can't be selected.

**Rendering skill content safely.** Skill files come from untrusted repositories.
Markdown is rendered on the server, with these rules:

- Raw HTML in the Markdown is escaped and shown as text. It is never interpreted.
- Only `http:`, `https:` and `mailto:` link targets are kept. Other schemes, such as
  `javascript:`, are removed.
- The page rewrites relative links to the skill's directory on GitHub at the scanned
  commit, e.g. `reference.md` →
  `https://github.com/<repository>/blob/<commit>/<path>/reference.md`.
- Every link opens in a new tab with `rel="noopener noreferrer"`.
- Images from other hosts are not loaded, because the Content-Security-Policy is
  `default-src 'self'`.

**API.** `GET /api/scan?url=<repository-url>` returns JSON:

```json
{
  "repository": {"name": "acme/skills", "description": "Acme agent skills", "branch": "main", "commit": "<sha>"},
  "skills": [
    {"name": "pdf-extract", "description": "<full>", "short_description": "<shortened>", "path": "skills/pdf",
     "also_at": [], "shipped": false, "warnings": [],
     "content": "---\nname: pdf-extract\n…", "content_html": "<h1>PDF</h1>\n…"}
  ],
  "ignored": [{"path": "src/test/resources/skills/demo", "reason": "test data"}]
}
```

`content` is the skill file's exact text, and `content_html` is its rendered Markdown
body (see "Rendering skill content safely" above). Both are `null` when the content isn't
available.

Failures return `{"error": "<message>", "exit_code": <code>}`, with the message and code
from section 7. The HTTP status depends on the exit code:

| Exit code | HTTP status |
|-----------|-------------|
| `2` (usage), or no `url` parameter | `400` |
| `3` (repository not found) | `404` |
| `4` (branch not found) | `422` |
| `5` (network or rate limit) | `502` |
| `1` (unexpected) | `500` |

Every successful scan made through the API appends one line to the scan log.

**Security:**
- The server listens on `127.0.0.1` only.
- It rejects requests whose `Host` header is not `127.0.0.1:<port>` or
  `localhost:<port>` with `403`. This blocks DNS rebinding.
- It answers only `GET`; other methods get `405`.
- It serves a strict `Content-Security-Policy` (`default-src 'self'`).
- The page inserts repository content as text, never as HTML.

## 6. Scan log

Every successful scan is logged, in addition to the report on stdout.

- **Location:** `$XDG_STATE_HOME/skill-atlas/scans.log`, or
  `~/.local/state/skill-atlas/scans.log` when `XDG_STATE_HOME` is not set.
  The directory is created if it doesn't exist.
- **Format:** JSON Lines, one object per scan, appended to the file:

```json
{"scanned_at":"2026-09-30T10:28:00Z","repository":"anthropics/skills","description":"Public repository for Agent Skills","branch":"main","commit":"3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39","skill_count":3}
```

- If the log cannot be written, print a warning to stderr. The scan itself still
  succeeds and its exit code is unchanged.

## 7. Errors and exit codes

| Code | Meaning | Example message (stderr) |
|------|---------|--------------------------|
| `0` | Scan completed, including when zero skills were found | |
| `1` | Unexpected internal error | `error: unexpected failure: <details>` |
| `2` | Invalid usage or URL | `error: not a GitHub repository URL: https://gitlab.com/a/b` |
| `3` | Repository not found or not accessible | `error: repository owner/repo not found (is it private? set GITHUB_TOKEN)` |
| `4` | Default branch cannot be cloned, e.g. the repository is empty | `error: branch 'main' not found in owner/repo (is the repository empty?)` |
| `5` | Network or GitHub API failure, including rate limiting | `error: GitHub API rate limit exceeded; set GITHUB_TOKEN to raise the limit` |
| `130` | Interrupted with Ctrl-C | `error: scan interrupted` |

Problems in individual skill files are never errors. They show up as warnings on that
skill (section 4.3).

## 8. Non-functional requirements

- **Dependencies:** needs `git` on `PATH`. If it is missing, exit `1` with a clear message.
- **Performance:** a repository under 100 MB with up to 500 skills should scan in
  under 30 seconds on a typical broadband connection. The shallow clone keeps download
  size small.
- **Security:** never run code from the scanned repository. Never print `GITHUB_TOKEN`.
  Only read files inside the cloned directory.
- **Determinism:** scanning the same commit twice gives identical output, apart from
  `scanned_at`.
- **Platforms:** macOS and Linux. Windows is desirable but not required for v1.

## 9. Implementation

- **Language:** Kotlin on the JVM (Java 21+), built with Gradle.
- **Command-line parsing:** Clikt.
- **Terminal UI:** Mosaic (`com.jakewharton.mosaic:mosaic-runtime`) with the Kotlin
  Compose compiler plugin. The report is a set of `@Composable` functions.

## 10. Acceptance criteria

1. `skill-atlas scan https://github.com/<owner>/<repo>` on a public repo with skills
   prints the repository name, description, and full commit SHA, followed by every
   skill's name and description.
2. Every URL form in section 3.3 resolves to the same repository.
3. A repository with no `SKILL.md` files prints `No skills found.` and exits `0`.
4. A skill with broken frontmatter is still listed, with a warning, and the other
   skills are unaffected.
5. Each successful scan appends exactly one line to the scan log.
6. Every error condition in section 7 exits with its listed code and message.
7. The temporary clone directory is gone after both successful and failed runs.
8. In a terminal, the scan shows a live status line, then the highlighted report from
   section 5.1.
9. When stdout is piped, e.g. `skill-atlas scan <url> | cat`, the output is the plain
   format from section 5.2 with no escape codes.
10. No skill description in either format is longer than 100 characters plus `…`.
11. `skill-atlas serve` shows the same results in a browser at `http://127.0.0.1:8421/`,
    with the highlights from section 5.4.
12. JetBrains/MPS is reported as 41 skills with their `.claude` and product copies
    merged; JetBrains/koog's test fixtures are ignored; JetBrains/android's
    `agent/skills` are listed (section 4.4).
13. In the web view, clicking any skill in the left list shows that skill's content in
    the right pane (section 5.4).

## 11. Testing

### 11.1 Unit tests

In-process tests in `src/test/kotlin` cover each component on its own: URL parsing,
frontmatter parsing, skill discovery, report rendering, the scan log, and the command
wiring. They run with `./gradlew test`.

### 11.2 CLI integration tests

Integration tests in `src/integrationTest/kotlin` run the **real, installed CLI**
(`build/install/skill-atlas/bin/skill-atlas`) as a separate process and assert on what
it actually produces:

- the exact stdout, character for character
- the exact stderr
- the exit code
- the scan log line
- that the temporary clone directory is gone afterwards

They run with `./gradlew integrationTest`, which builds the distribution first. They are
part of `./gradlew check` and `./gradlew build`, so they always run both locally and on
CI.

**Determinism.** An integration test gives the same result on every machine and every
run:

- **No network.** Nothing talks to github.com. Each test serves the GitHub API from a
  stub HTTP server on `127.0.0.1` and clones from local git repositories.
- **Fixed fixtures.** Fixture repositories are created during the test with a fixed
  author, committer, email, and timestamp, so the same content always produces the
  same commit SHA.
- **Isolated environment.** The CLI process runs with:
  - `GITHUB_TOKEN` removed
  - `GIT_CONFIG_GLOBAL=/dev/null` and `GIT_CONFIG_NOSYSTEM=1`, so the developer's git
    configuration cannot change the outcome
  - `XDG_STATE_HOME` and `java.io.tmpdir` pointing into per-test temporary directories
- **Fixed terminal.** The rich view is tested inside a pseudo-terminal with a fixed size
  of 100×40.
- **No timing assumptions.** Tests never sleep to wait for something to happen. They wait
  for a specific marker in the output, with a timeout that only exists to fail a hung
  test.

**Test hooks.** Two environment variables let tests point the CLI at local stand-ins.
They are meant for tests only.

| Variable | Default | Effect |
|----------|---------|--------|
| `SKILL_ATLAS_GITHUB_API_URL` | `https://api.github.com` | Base URL of the GitHub REST API |
| `SKILL_ATLAS_GIT_BASE_URL` | `https://github.com` | Repositories are cloned from `<base>/<owner>/<repo>.git` |

**Required scenarios:**

| Scenario | Asserts |
|----------|---------|
| Scan a repository with valid, broken, and incomplete skills, plus a skipped `node_modules` skill | Exact plain report and progress messages; exit `0`; one scan log line; temp directory removed |
| Every URL form from section 3.3 | Identical report for each form |
| Repository without skills and without a description | `Description: (none)` and `No skills found.`; exit `0` |
| Invalid URL | Exit `2` and the exact message; stdout is empty |
| Unknown repository (API returns 404) | Exit `3` and the exact message |
| Empty repository (the default branch doesn't exist) | Exit `4` and the exact message; temp directory removed |
| Rate limited (API returns 403 with `x-ratelimit-remaining: 0`) | Exit `5` and the exact message |
| `--help`, `--version`, no arguments, unknown option | Exit `0`, `0`, `2`, `2` |
| Piped stdout | No escape codes anywhere in the output |
| Rich view in a pseudo-terminal | Escape codes are present, and the report text matches section 5.1 |
| Ctrl-C while fetching metadata, in the rich view | Exit `130`, `error: scan interrupted`, temp directory removed |
| Identical copies in `.agents` and `.claude` (MPS) | One entry, main path `.agents/…`, `also in .claude/…` |
| Unusual folder `agent/skills` (android) | Listed like any other skill |
| Copy under `plugins/…/resources/…` (MPS) | Merged into the same entry; labeled shipped in product |
| Fixture under `src/jvmTest/resources` (koog) | Not listed as a skill; shown under the ignored test fixtures |
| Same name, different content | Both listed with `duplicate name` |
| The same edge cases through the web API | Exact `also_at`, `shipped` and `ignored` JSON |
| Web API skill content | Exact `content` and `content_html`; raw `<script>` is escaped; `javascript:` links are removed |
| Browser: split pane | Clicking a list item selects it and shows its full description, paths, GitHub link, and rendered content on the right |
| Browser: keyboard and tabs | ↓ selects the next skill; the Raw tab shows the exact file text; `&skill=` in the URL reopens the selection |
| `serve --port 0` | Prints the URL. `GET /` returns the page; `/app.js` and `/style.css` return the assets |
| Web API scan | Exact JSON body; one scan log line; temp directory removed |
| Web API errors | Invalid URL `400`, unknown repository `404`, missing `url` `400`; exact JSON bodies |
| Web security | Foreign `Host` header `403`; `POST` `405`; unknown path `404`; CSP header present |

### 11.3 Continuous integration

The GitHub Actions workflow `.github/workflows/ci.yml` runs `./gradlew build` on every
push to any branch and on every pull request. It runs on `ubuntu-latest` and
`macos-latest` with Java 21. When a run fails, the test reports are uploaded as an
artifact.

The test tools needed are `git`, a JDK, and `python3` (used to run the CLI in a
pseudo-terminal). All three are preinstalled on GitHub-hosted runners.

**Browser tests** are part of the integration tests. They drive the real page in
headless Chromium through Playwright for Java, against a `serve --port 0` process with
the usual stub API and fixture repositories. They wait for page elements, never for a
fixed time. Playwright downloads Chromium on first use into `~/.cache/ms-playwright`
(`~/Library/Caches/ms-playwright` on macOS). CI caches that folder, and on Linux it
installs the browser's system libraries first with Playwright's `install-deps` command.

## 12. Definition of done

Every change goes through a pull request, managed with the GitHub CLI (`gh`). A change is
done only when **all** of the following are true:

1. **`./gradlew build` passes locally.** This includes every unit test and every CLI
   integration test. No test may be skipped or disabled to make it pass.
2. **The branch is pushed and has a pull request,** opened with
   `gh pr create --base main --fill` or with an explicit title and body.
3. **CI is green on the pull request** for its latest commit, on every platform in the
   matrix. Watch it with `gh pr checks <pr> --watch`.
4. **The pull request is merged** with `gh pr merge <pr> --merge --delete-branch`.

If CI on the pull request is red:

1. Find the failing run, e.g. `gh pr checks <pr>` or `gh run list --branch <branch>`.
2. Read the failing job's logs with `gh run view <run-id> --log-failed`. If the test
   reports are needed, download them with `gh run download <run-id>`.
3. Fix the root cause. Don't skip, disable, or loosen the failing test.
4. Confirm `./gradlew build` passes locally, then commit and push to the same branch.
5. Repeat until CI is green, then merge.

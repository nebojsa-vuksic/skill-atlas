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
skill-atlas
skill-atlas shell
skill-atlas scan <github-project-url>... [--filter <words>] [--skill <name-or-path>]
skill-atlas browse <github-project-url>
skill-atlas serve [--port <port>]
skill-atlas --help
skill-atlas --version
```

`skill-atlas` with no arguments opens the interactive shell (section 5.9) when stdin and
stdout are both terminals. Otherwise it prints the usage to stderr and exits `2`, as
before.

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
| `-f, --filter <words>` | | `scan` only. List only the skills that match `<words>`, by the rules in section 5.5 (section 5.7). |
| `-s, --skill <name-or-path>` | | `scan` only. Show one skill's details, similar skills, and content instead of the list (section 5.7). |

`--filter` and `--skill` can't be used together. Passing both exits `2` with
`error: --filter and --skill can't be used together`.

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
┌────────────────────────────────┬─┬──────────────────────────────────────────────┐
│ 🔍 test                 5 of 41 │ │ mps-tests  ◆ shipped in product               │
├────────────────────────────────┤ │ Use when writing or modifying tests inside…   │
│ mps-aspect-typesystem           │ │ .agents/skills/mps-tests                      │
│ …WhenConcrete test statement…   │ │ also in .claude/skills/mps-tests              │
│                                 │ │ View on GitHub ↗                              │
│ mps-build-language              │ │                                               │
│ …module tests, or run code…     │ │ Similar skills                                │
│                                 │ │ mps-run-configurations   ▓▓▓▓▓▓░░░░   62 %    │
│▌mps-tests                  ◆    │ │ mps-aspect-typesystem    ▓░░░░░░░░░   12 %    │
│▌Use when writing or modifyi…   │ │                                               │
│                                 │ │ [ Rendered | Raw ]                            │
│                                 │ │ # MPS tests …                                 │
└────────────────────────────────┴─┴──────────────────────────────────────────────┘
   filter + skill list (left)     divider       selected skill (right)
```

- **Left, the skill list.** The filter field (section 5.5) sits at the top of the left
  pane and stays visible while the list scrolls. Items are buttons. Clicking an item, or
  pressing Enter or Space, selects it. ↑ and ↓ move the selection. The list scrolls on
  its own.

  **List items are compact.** Each item shows only two things:
  - **Name:** one line, bold, cut with `…` if too long.
  - **Short description:** at most two lines, muted, and `(no description)` when
    missing.

  Paths, `also in` copies, and full labels appear only in the right pane. In the list,
  labels shrink to small icons at the right end of the name line, each with a tooltip:
  - `◆` for *shipped in product*
  - `⚠` for warnings, with the warnings as the tooltip
  - `⧉ <n>` when the skill has `<n>` identical copies

  Items are separated by a thin line instead of each having a border, with 12 px of
  vertical padding. Hovering an item tints its background. The selected item gets a 3 px
  accent bar on the left, a tinted background, and `aria-selected="true"`.
- **Right, the selected skill.** This pane shows:
  - the skill name and its badges
  - the **full** description, not the shortened one
  - the main path and its `also in` copies
  - a **View on GitHub** link to
    `https://github.com/<repository>/blob/<commit>/<path>/SKILL.md`
  - the **Similar skills** section (section 5.6)
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
  kept in the page URL as `&skill=<path>`, and the filter as `&q=<query>`. A link
  therefore reopens the same repository with the same filter and the same skill
  selected.
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
     "content": "---\nname: pdf-extract\n…", "content_html": "<h1>PDF</h1>\n…",
     "similar": [{"path": "skills/docx", "name": "docx", "score": 31}]}
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

### 5.5 Filter

The filter narrows the skill list in the web view by words in the skills' names and
descriptions. It runs in the page and needs no API call.

- **Field.** A search field with a 🔍 icon and the placeholder `Filter skills` sits at the
  top of the left pane. On its right, a count pill shows `<visible> of <total>`, e.g.
  `5 of 41`. The pill is accent-colored while a filter is active and muted otherwise.
- **Matching:**
  - The query is split on whitespace into words.
  - Matching is case-insensitive.
  - A skill matches when **every** word is a substring of its name or of its **full**
    description.
  - An empty query shows every skill.
  - The list updates on every keystroke and keeps the original order.
- **Highlighting.** Matched text is wrapped in `<mark>` in each visible item's name and
  description line. If a word matches only past the shortened description, the item's
  description line switches to a snippet around the first match: up to 40 characters
  on each side, cut at word boundaries, with `…` where text was left out, e.g.
  `…WhenConcrete test statement…`.
- **Selection:**
  - If the selected skill is filtered out, the first visible skill is selected.
  - If nothing matches, the list shows `No skills match "<query>".` and the right pane
    shows nothing.
  - Clearing the filter keeps the current selection.
- **Keyboard:**
  - `/` focuses the field when focus isn't already in a text field.
  - Escape in the field clears it.
  - ↓ in the field moves focus to the selected item.
- **URL.** The query is kept as `&q=<query>`, so a link reopens the same filter.

### 5.6 Similar skills

The right pane lists the skills that are most similar to the selected one. Similarity
is a **deterministic heuristic, not AI**: TF-IDF cosine similarity over words. The server
computes it once per scan, over the listed skills of that repository.

**Words.** Each skill's text is its name, its full description, and the first 20,000
characters of its `SKILL.md` body (the Markdown after the frontmatter).

- The text is lowercased and split on anything that isn't a letter or digit, so
  `mps-tests` gives `mps` and `tests`.
- Dropped: words shorter than 3 characters, words made only of digits, and words in a
  fixed English stopword list (`the`, `and`, `when`, `use`, `with`, `this`, `that`,
  `for`, `from`, `you`, `your`, …).
- A trailing `s` is removed from words longer than 4 characters that don't end in `ss`,
  so `tests` and `test` count as the same word.

**Weights:**
- Where a word appears changes how much it counts: a name word counts 3, a description
  word 2, and a body word 1. These add up to the word's weight in the skill.
- Each weight is multiplied by `idf = ln((N + 1) / (df + 1)) + 1`, where `N` is the
  number of listed skills and `df` is the number of skills that contain the word.
- Two skills' similarity is the cosine of their weighted word vectors, from 0 to 1.

**Result.** Each skill lists at most 5 other skills with a similarity of at least 0.05,
sorted by similarity (highest first), then by path. The score is shown as a whole
percentage, `round(similarity × 100)`. Identical copies were already merged (section
4.4), so a skill never lists its own copies.

**Web view.** The *Similar skills* section sits in the right pane, between the GitHub link
and the content tabs.
- **Rows.** Each row shows the other skill's name (bold), its path (muted, monospace), a
  bar filled to the score, and the percentage, e.g. `62 %`.
- **Clicking a row** selects that skill. If the filter hides it, the filter is cleared
  first.
- **No similar skills:** the section shows `No similar skills found.`

**API.** Each skill in `GET /api/scan` gets
`"similar": [{"path": "<path>", "name": "<name>", "score": <whole percent>}]`, in the
order above. The list is empty when there are no similar skills.

**Performance.** Vectors are sparse, and every pair is compared once. A repository with
500 skills must finish this step in under 1 second.

### 5.7 Filter and skill details in the CLI

The web view's filter and right pane are also available in `scan`, for scripts and for
quick lookups. They use the same core as the web view: the filter rules from section 5.5
(ported to Kotlin) and the similarity from section 5.6. As with the rest of `scan`, the
output is the rich view in a terminal and plain text when stdout is piped (section 5).

**`scan <url> --filter <words>`** narrows the list.

- **Which skills are listed:** only matching skills, in their usual order. A skill
  matches when every word is in its name or full description, ignoring case.
- **Description line:** the same rule as the web view. If a word appears only past the
  shortened description, the line shows a snippet around the first match instead.
- **Header, plain format:** `Found <total> skills, <n> match "<words>":`, or
  `Found <total> skills, none match "<words>".` when nothing matches.
- **Header, rich view:** `<n> of <total> skills match "<words>"` in bold green, or
  `No skills match "<words>".` in yellow when nothing matches.
- **Highlighting, rich view only:** matched text in names and description lines is shown
  black on yellow.
- Paths, `also in` lines, tags, and the ignored test fixtures section are the same as
  without a filter.

Plain example:

```
Found 41 skills, 2 match "test run":

  mps-run-configurations  [shipped in product]
    Use when creating, editing or debugging MPS run configurations — Java classes with…
    .agents/skills/mps-run-configurations
    also in .claude/skills/mps-run-configurations

  mps-tests  [shipped in product]
    …
```

**`scan <url> --skill <name-or-path>`** prints one skill in place of the list, with the
same information as the web view's right pane.

- **Finding the skill:** the value first matches any skill directory path, including
  `also in` copies. Otherwise it matches a skill name, ignoring case.
- **No match:** exit `6` with `error: no skill '<value>' in <owner>/<repo>`.
- **Several skills with that name:** exit `2` with
  `error: skill name '<value>' matches <n> skills: <path>, <path>; pass a path instead`.

Plain format:

```
Repository:  JetBrains/MPS
Description: JetBrains Meta programming System
Commit:      49d37b63488a0a8e42eb0130cb867fd508f398ac (master)

Skill:       mps-tests  [shipped in product]
Path:        .agents/skills/mps-tests
Also in:     .claude/skills/mps-tests
             plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-tests
GitHub:      https://github.com/JetBrains/MPS/blob/49d37b…/.agents/skills/mps-tests/SKILL.md

Description:
  Use when writing or modifying tests inside MPS `@tests` models — …

Similar skills:
  mps-language-aspects-overview  ████░░░░░░  42 %  .agents/skills/mps-language-aspects-overview
  mps-mcp-workflow               ████░░░░░░  39 %  .agents/skills/mps-mcp-workflow

SKILL.md:
  ---
  name: mps-tests
  …
```

Rules for the plain format:
- **`Also in:`** is left out when there are no copies. Tags and warnings follow the name
  as in section 5.2.
- **Description:** the **full** description, never shortened, with each line indented
  two spaces. `(no description)` when it's missing.
- **Similar skills rows:** the name padded to the longest similar name, then a 10-cell
  bar with `round(score / 10)` `█` cells and the rest `░`, then the score right-aligned
  as `NN %`, then the path. `No similar skills found.` when there are none.
- **`SKILL.md:`** the exact file text, each line indented two spaces, or
  `(content not available)`.

The rich view has the same sections, with the styles of section 5.1:
- labels dim
- skill name bold cyan
- bar cyan
- score bold
- paths dim

A `--filter` or `--skill` scan is still a scan: it appends one line to the scan log.

### 5.8 Interactive browser (`browse`)

`skill-atlas browse <url>` scans like `scan`, then opens a full-screen, keyboard-driven
version of the web view in the terminal. It is built with Mosaic.

- **Needs a terminal.** If stdin or stdout isn't a terminal, it exits `2` with
  `error: browse needs an interactive terminal; use "skill-atlas scan" instead`.
- **Scanning.** It shows the same live status line as `scan` (section 5.1). Scan errors
  exit with the codes and messages of section 7, and a successful scan appends one line
  to the scan log.
- **Size.** The view fills the terminal's current size, minus one row so the last line
  never scrolls the screen, and it adapts when the terminal is resized. Below 60×10 it
  shows only `Terminal too small: browse needs at least 60×10.`

```
 SKILL ATLAS  JetBrains/MPS  49d37b63488a master
 / test▏                  5 of 41 │ mps-tests  ◆ shipped in product
 ─────────────────────────────────│ Use when writing or modifying tests inside MPS
 mps-aspect-typesystem      ◆ ⧉2  │ `@tests` models — `NodesTestCase` (typesystem, …
 …WhenConcrete test statement…    │ .agents/skills/mps-tests
                                  │ also in .claude/skills/mps-tests
▌mps-tests                  ◆ ⧉2  │
▌Use when writing or modifying t… │ Similar skills
                                  │ ▸ mps-language-aspects-overview  ████░░░░░░  42 %
 mps-run-configurations     ◆ ⧉2  │   mps-mcp-workflow               ████░░░░░░  39 %
 …JUnit Tests for ITestCase…      │
                                  │ SKILL.md
                                  │ ---
                                  │ name: mps-tests
 ↑↓ select  / filter  tab similar  pgup/pgdn scroll  q quit
```

**Layout:**
- **Top line:** ` SKILL ATLAS ` (bold, inverted), the repository name (bold cyan), the
  first 12 characters of the commit (yellow), and the branch (magenta).
- **Left pane:** 40 % of the width, clamped to between 28 columns and the width minus 40.
  - It starts with the filter line: `/ ` and the query, with a `▏` cursor while the
    filter has focus, or a dim `/ filter` placeholder when empty. The `<n> of <total>`
    count is right-aligned on the same line, accent-colored while a filter is active.
  - A rule follows, then the skills, each with the compact look of section 5.4: a name
    line with icons (`◆`, `⚠`, and `⧉n` written without a space, e.g. `⧉2`) at the right
    end, and one dim description line.
    Both lines are cut with `…` to fit, and a blank line follows each skill.
  - Matches are highlighted as in section 5.7. The selected skill has a `▌` accent bar and
    a bold name.
  - The list scrolls to keep the selection visible. With no match, it shows
    `No skills match "<query>".`
- **Right pane,** after a `│` column: the selected skill.
  - The name with its tags.
  - The full description, wrapped, at most 6 lines, then `…`.
  - The path and its `also in` copies, dim.
  - `Similar skills`, with rows like the ones in section 5.7.
  - `SKILL.md`, with the file text wrapped to the pane width.
  - Content that doesn't fit is scrolled with PgUp and PgDn.
- **Bottom line:** the key help for the current focus, dim.

**Keys.** There are three focus areas: the list (the default), the filter, and similar
skills.

| Focus | Key | Effect |
|-------|-----|--------|
| List | ↑ / ↓, Home / End | Select the previous / next, first / last visible skill; the right pane scrolls back to the top |
| List | `/` | Focus the filter |
| List | Tab | Focus similar skills (if the selected skill has any) |
| List | Esc | Clear the filter |
| List | PgUp / PgDn | Scroll the right pane by its height minus one line |
| List | `q`, Ctrl-C | Quit |
| Filter | printable characters, Backspace | Edit the query; the list updates at once |
| Filter | Enter, ↓ | Back to the list, keeping the query |
| Filter | Esc | Clear the query and go back to the list |
| Similar | ↑ / ↓ | Move the `▸` cursor |
| Similar | Enter | Select that skill (clearing the filter first if it hides it) and go back to the list |
| Similar | Tab, Esc | Back to the list |

Ctrl-C quits from any focus. Filtering follows section 5.5: it keeps the selection when
the skill is still visible, and otherwise selects the first visible skill. Only
printable ASCII characters can be typed into the filter, because that's what Mosaic
reports as keys. Quitting puts the terminal back as it was and exits `0`.

### 5.9 Interactive shell (`shell`)

`skill-atlas shell`, or `skill-atlas` with no arguments in a terminal, opens an
interactive shell in the style of the Claude Code CLI. You type slash commands at a
prompt, with a command palette that filters as you type. The shell keeps one scanned
repository, the **current repository**, which the other commands work on. It is built
with Mosaic, and it uses the same core and the same rich renderers as `scan` (sections
5.1 and 5.7).

- **Needs a terminal.** If stdin or stdout isn't a terminal, `shell` exits `2` with
  `error: shell needs an interactive terminal; use "skill-atlas scan" instead`. Without
  a terminal, `skill-atlas` with no arguments prints the usage and exits `2` (section 3.1).
- **Scrollback and live area.** Only the prompt and the palette are live, at the bottom
  of the terminal. Everything a command prints goes above them as permanent scrollback,
  printed once through Mosaic's static output. Each command is first echoed there as a
  dim `❯ <input>` line, and each command's output is followed by a blank line, so the
  scrollback reads like a transcript.

```
❯ /scan https://github.com/anthropics/skills
  SKILL ATLAS

 Repository   anthropics/skills
 …

❯ /s█
▸ /scan <url>              Scan a GitHub repository and make it the current one
  /skill <name-or-path>    Show one skill: description, paths, similar skills, SKILL.md
  /similar <name-or-path>  Show the skills most similar to one skill
  /serve [port]            Start the web view in the background; /serve stop stops it
```

**Prompt.** A bold cyan `❯ `, then the input with a block cursor (inverted). While the
input is empty, the dim hint `type / for commands` follows the cursor. Input that is too
wide for the terminal scrolls sideways to keep the cursor visible, with `…` at the start.

**Command palette.** When the input starts with `/` and has no space yet, a list of
matching commands appears under the prompt. Each row shows the command, its arguments,
and a dim one-line description.

- **Matching:** the text after `/` is matched against command names, ignoring case. An
  exact match comes first, then names that start with the text, then names that contain
  it. Within each group, commands keep the order of the table below. The matched part of
  each name is highlighted black on yellow. With only `/` typed, every command is listed.
- **Rows:** `▸ ` on the selected row and two spaces on the others, so the commands line
  up with the input. Then the command and its arguments, padded to the widest one in the
  list plus two spaces, then the description. Rows are cut with `…` to fit the width.
- **Selection:** the first row is selected, shown with a cyan `▸` and a bold cyan name.
  ↑ and ↓ move it and stop at the ends. Editing the input selects the first row again.
- **Size:** at most 8 rows are shown. The list scrolls to keep the selection visible.
- **Closing:** Esc closes the palette until the input is edited again. When nothing
  matches, the palette is hidden.
- **Tab** completes the selected command into the input: `/<name> ` with a trailing space
  for commands that take an argument, `/<name>` otherwise.
- **Enter** runs the selected command. If the command needs an argument, Enter completes
  it like Tab instead.

**Skill names.** Once there is a current repository, typing `/skill ` or `/similar `
(for example after completing it with Tab) opens the same palette with the repository's
skills. Each row shows a skill name and its shortened description (section 5.3), matched
and ranked against the text after the space by the same rules as commands. A name that
several skills share is offered as each skill's path instead, so every suggestion is
unambiguous. Tab completes the selection into the input, and Enter runs the command on
it. If nothing matches, for example because a path was typed, the palette is hidden and
Enter runs the input as typed.

**Commands:**

| Command | Needs a repository | Effect |
|---------|--------------------|--------|
| `/scan <url>` | no | Scans like `scan` and makes the result the current repository. Prints the rich report (section 5.1). |
| `/filter <words>` | yes | Lists the matching skills, like `scan --filter`, in the rich view (section 5.7). Without words, lists every skill. |
| `/skill <name-or-path>` | yes | Shows one skill, like `scan --skill` (section 5.7). |
| `/similar <name-or-path>` | yes | Shows only that skill's similar-skills table: `Similar to <name>`, then the rows of section 5.7. |
| `/browse` | yes | Opens the full-screen `browse` view (section 5.8) on the current repository. `q` or Ctrl-C returns to the shell. |
| `/repo` | yes | Shows the current repository's summary: name, description, commit and branch, and the skill count. |
| `/serve [port]` | no | Starts the web view (section 5.4) in the background, on port `8421` unless given, `0` for any free port, and prints `Skill Atlas web view: <url>`. `/serve stop` stops it. |
| `/log` | no | Shows the last 10 entries of the scan log (section 6), oldest first, one per line: time (dim), repository (bold cyan), the first 12 characters of the commit (yellow), branch (magenta), and `<n> skills`, in aligned columns. `No scans logged yet.` when the log is empty or missing. |
| `/help` | no | Lists the commands and the keys. |
| `/quit` | no | Leaves the shell. |

- **No repository yet.** A command that needs a repository says
  `No repository yet — run /scan <url> first.` in yellow, and does nothing else.
- **Missing arguments.** `/scan`, `/skill` and `/similar` without an argument print
  `error: usage: /<command> <argument>`, e.g. `error: usage: /scan <url>`.
- **Unknown commands** print `error: unknown command /<name>; type /help for the list`.
- **Text without `/`.** Enter on input that doesn't start with `/` is a `/filter` with
  that text on the current repository. Without a repository it prints the
  "No repository yet" message.
- **`/serve`.** If the web view is already running, `/serve` prints
  `The web view is already running: <url>`. `/serve stop` prints `Stopped the web view.`,
  or `The web view isn't running.` A port outside 0 to 65535, or any other argument,
  prints `error: usage: /serve [port] or /serve stop`. A taken port prints
  `error: could not listen on 127.0.0.1:<port>: <reason>`. Leaving the shell stops the
  web view. Its scans append to the scan log, as with `serve`.

**Keys at the prompt:**

| Key | Effect |
|-----|--------|
| printable characters | Insert at the cursor |
| Backspace / Delete | Delete the character before / under the cursor |
| ← / →, Home / End, Ctrl-A / Ctrl-E | Move the cursor |
| Ctrl-U | Delete everything before the cursor |
| ↑ / ↓ | Palette open: move the selection. Palette closed: walk the input history |
| Tab | Palette open: complete the selection |
| Enter | Palette open: run (or complete) the selection. Otherwise: run the input |
| Esc | Close the palette |
| Ctrl-C | Clear the input; on an empty input, quit |
| Ctrl-D | On an empty input, quit |

- **History.** Every non-empty input that is run is added to this session's history,
  except a repeat of the previous entry. ↑ steps back through it, ↓ forward, and going
  past the newest entry brings back what was typed before walking the history. A
  recalled input keeps the palette closed until it is edited, so ↑ and ↓ keep walking
  the history. The history is not saved between sessions.
- **Characters.** Any printable character can be typed, including non-ASCII ones such as
  `é` or a pasted `—`. Mosaic only reports printable ASCII and its named keys, and ends
  the program on anything else, so the shell reads the terminal's key events itself and
  passes only those keys on to Mosaic. Other keys, such as ones with no name, are
  ignored.

**Running commands.**
- **Scanning.** During `/scan`, the prompt is replaced by the spinner status line of
  section 5.1. Other keys are ignored while it runs. Ctrl-C cancels just that scan: it
  prints `error: scan interrupted`, keeps the previous current repository, and returns
  to the prompt.
- **Errors.** The messages of section 7 are printed inline as `error: <message>` in red,
  and the shell keeps running. Exit codes don't apply inside the shell.
- **Scan log.** Each successful `/scan` appends one line to the scan log (section 6). If it
  can't be written, the warning is printed inline in yellow.
- **Leaving.** `/quit`, Ctrl-D on an empty input, or Ctrl-C on an empty input stops the
  web view if it runs, puts the terminal back as it was, and exits `0`.

### 5.10 Several repositories, with search

`scan` and the web view work on several repositories at once. The filter searches across
all of them. `browse` and the interactive shell stay single-repository for now.

**Scanning.** Each repository is scanned exactly as in section 4, on its own. Up to 4 scans
run at the same time. The results are combined in the order the URLs were given.
Duplicate URLs, meaning the same `owner/repo` after normalizing, are scanned once. When one
repository fails, the others are still reported (see *Failures* below).

**Search syntax.** The filter of section 5.5 gains one qualifier, which works with one
repository or many:
- `repo:<text>` keeps only the skills of repositories whose `owner/name` contains `<text>`,
  ignoring case. For example, `repo:mps test` means "skills matching `test` in
  repositories whose name contains `mps`".
- Several `repo:` words are alternatives: a repository qualifies when it matches any of
  them.
- The other words match skill names and full descriptions, as before.
- `repo:` on its own, with no text, is ignored.

Highlighting and snippets apply to the other words only.

**Identity.** With several repositories, a skill is identified by `<owner>/<repo>:<path>`,
for example `JetBrains/MPS:.agents/skills/mps-tests`, because paths repeat across
repositories.

**Similar skills.** With several repositories, similarity (section 5.6) is computed over the
listed skills of **all** of them together. Similar skills from another repository show that
repository's name, so the list points you to related skills elsewhere.

#### CLI

`scan <url> [<url>...]` accepts one or more URLs.

- **One URL:** the output is exactly as before.
- **Several URLs, plain format:** each repository's report follows section 5.2, including
  the `--filter` header and items, separated by a line with 80 `─` characters. The report
  ends with a summary line: `Scanned <m> repositories: <n> skills` (with `--filter`:
  `Scanned <m> repositories: <k> of <n> skills match "<words>"`), and `, <f> failed` when
  any failed.
- **Several URLs, rich view:** the same sections and summary, with the styles of section
  5.1.
- **`--skill <selector>`** searches every repository. `<selector>` may be
  `<owner>/<repo>:<path>`. A plain path or name that matches skills in more than one
  repository is ambiguous: exit `2`, listing the matches as `<owner>/<repo>:<path>`.
  Similar skills can come from any of the repositories, and their paths are then written
  as `<owner>/<repo>:<path>`.
- **Scan log:** one line per successfully scanned repository.
- **Failures:** the report lists only the repositories that were scanned. Each failed
  repository prints its `error: <owner>/<repo>: …` line (the section 7 message, prefixed
  with the repository) to **stderr**, in URL order, after the report. The exit code is the
  code of the **first** failure in URL order. It is `0` only if every repository succeeded.
  `--skill` behaves the same way: it shows the skill if a scanned repository has it, and
  still exits with the first failure's code.

#### Web view

- **Adding repositories.** The repository field accepts several URLs separated by spaces,
  commas, or new lines. Each added repository appears as a **chip** under the field,
  showing `owner/name` and its skill count, with an `×` button that removes it. The Scan
  button scans the repositories that aren't loaded yet. The page URL keeps them all as
  repeated `url` parameters: `?url=a&url=b&q=…&skill=owner/repo:path`.
- **Summary.** For one repository, the summary is as before. For several, it's a compact
  table with one row per repository: name (linked), description, commit, branch, and skill
  count. A repository that failed shows its `error: …` message in its row, and the others
  still load.
- **Skill list.** Skills are grouped by repository, in the order the repositories were
  added. Each group has a header with `owner/name` and the number of visible skills.
  Headers stay pinned while their group scrolls. The filter and its `n of total` count
  cover all repositories, and a group whose skills are all filtered out is hidden. With one
  repository there is no group header.
- **Right pane.** It shows the selected skill's repository name above the skill name.
  Similar-skill rows from another repository show that repository name.
- **API.** `GET /api/scans?url=<a>&url=<b>` returns the combined result:

```json
{
  "repositories": [
    {"url": "https://github.com/acme/skills", "name": "acme/skills", "description": "…", "branch": "main",
     "commit": "<sha>", "skill_count": 3},
    {"url": "https://github.com/acme/missing", "error": "repository acme/missing not found (…)", "exit_code": 3}
  ],
  "skills": [
    {"repository": "acme/skills", "id": "acme/skills:skills/pdf", "name": "pdf-extract", "…": "the fields of /api/scan",
     "similar": [{"repository": "acme/other", "id": "acme/other:skills/docx", "path": "skills/docx", "name": "docx", "score": 31}]}
  ],
  "ignored": [{"repository": "acme/skills", "path": "src/test/resources/skills/demo", "reason": "test data"}]
}
```

  - The response is `200` whenever the request itself is valid, even if some repositories
    failed.
  - With no `url` parameter, or more than 10 of them, it returns `400` with an `error` and
    `exit_code: 2`.
  - `GET /api/scan` stays as it is, for one repository.
  - **Cache:** the server keeps each successfully scanned repository's result for **10
    minutes**, keyed by `owner/name`. Within that time `/api/scans` reuses the result
    without cloning again, and without adding another scan log line. Adding a repository
    therefore scans only the new one, and removing one scans nothing. Similar skills are
    always recomputed over the requested set. Failed repositories are never cached.

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
| `6` | `--skill` names no skill in the repository | `error: no skill 'pdf' in owner/repo` |
| `130` | Interrupted with Ctrl-C | `error: scan interrupted` |

`browse` and `shell` exit `2` when stdin or stdout isn't a terminal (sections 5.8 and
5.9). The shell exits `0` when it is left. Inside the shell, errors are printed inline
and don't end it; Ctrl-C during a `/scan` cancels only that scan.

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
14. Typing words into the filter shows only the skills whose name or description
    contains all of them, with the count `n of total` (section 5.5).
15. The right pane lists up to 5 similar skills with percentages, computed by the
    deterministic heuristic in section 5.6. Clicking one selects it.
16. `scan <url> --filter <words>` lists only the matching skills, using the same matches
    and snippets as the web view (section 5.7).
17. `scan <url> --skill <name-or-path>` prints the skill's full description, paths,
    GitHub link, similar skills with bars, and its exact `SKILL.md` (section 5.7).
18. `browse <url>` shows the filterable list and the selected skill side by side in the
    terminal, with the keys from section 5.8.
19. `skill-atlas` with no arguments, in a terminal, opens the shell of section 5.9;
    without a terminal it prints the usage and exits `2`. `skill-atlas shell` without a
    terminal exits `2` with the message from section 5.9.
20. In the shell, typing `/` opens the command palette; it filters and highlights as you
    type, ↑/↓ select, Tab completes and Enter runs (section 5.9).
21. `/scan <url>` in the shell makes that repository current; `/filter`, `/skill`,
    `/similar`, `/repo` and `/browse` then work on it, and Tab after `/skill ` completes
    its skill names. Each successful `/scan` appends one scan log line.
22. Errors inside the shell are printed inline in red and the shell keeps running;
    Ctrl-C cancels a running `/scan` only.
23. `scan <url> <url>` reports both repositories, and one failing repository doesn't hide
    the other (section 5.10).
24. The web view loads several repositories, groups their skills, and searches across all
    of them, including with `repo:<text>`. Similar skills can point into another
    repository (section 5.10).

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
| Browser: compact list items | Items show only the name and at most two description lines; paths appear only in the right pane; icons `◆`, `⚠` and `⧉ n` have tooltips |
| Browser: filter | Typing `test` shows only matching skills in their original order, `<mark>`s the matches, and updates the count pill (`n of total`); a word found only in the full description shows a snippet; no match shows the empty message; Escape clears; `&q=` reopens the filter |
| Browser: filter and selection | Filtering out the selected skill selects the first visible one |
| Similar skills: heuristic | Fixed fixture skills give exact scores and order; stopwords, short words and digits are ignored; the top 5 and the 0.05 cutoff apply; the output is identical on every run |
| Similar skills: API | Each skill's exact `similar` array |
| Browser: similar skills | The right pane lists similar skills with bars and percentages; clicking one selects it and clears a filter that hid it |
| CLI filter: shared rules | The Kotlin filter gives the same matches, merged highlight ranges, and snippets as the web view on the same inputs |
| `scan --filter` | Exact plain output for a match, several words, a snippet, and no match; one scan log line |
| `scan --skill` | Exact plain output by name and by copy path; similar rows with bars; the exact `SKILL.md`; not found exits `6`; an ambiguous name and `--filter` with `--skill` exit `2` |
| `scan --filter` / `--skill` in a terminal | Escape codes are present, and the text of the rich view matches section 5.7 |
| `browse` without a terminal | Exit `2` and the exact message |
| `browse` state | Unit tests for the three focus areas and every key in section 5.8, selection while filtering, and scrolling |
| `browse` screen | Mosaic snapshot tests of the rendered frame at a fixed size |
| Several repositories: search | `repo:` qualifier, alternatives, and combination with words; highlighting ignores `repo:` |
| `scan` with several URLs | Exact plain output for two repositories, with separators and the summary; one failing repository (exit code of the first failure, and the other still reported); a duplicate URL is scanned once; one scan log line per repository |
| `scan --skill` across repositories | `owner/repo:path` selector; a name found in two repositories exits `2` listing both; similar skills from another repository use `owner/repo:path` |
| Web API `/api/scans` | Exact JSON for two repositories, including similar skills across them; a failed repository in its row with `200`; no `url` or more than 10 gives `400` |
| Browser: several repositories | Adding two repositories shows chips and grouped lists; a `repo:` search narrows to one group; removing a chip drops its group; the URL restores repositories, filter, and selection; a similar skill in another repository opens it |
| `browse` in a pseudo-terminal | Scripted keys (type a filter, move, jump to a similar skill, quit), waiting for markers on screen; exit `0`; one scan log line |
| `skill-atlas` with no arguments, without a terminal | Exit `2`; the usage on stderr; stdout empty |
| `shell` without a terminal | Exit `2` and the exact message; no scan log |
| `shell` palette | Unit tests: exact, prefix and substring ranking, case-insensitivity, highlight ranges, the 8-row window |
| `shell` completion | Unit tests: commands with and without arguments, skill names after `/skill ` and `/similar `, shared names offered as paths, no suggestions without a repository |
| `shell` history | Unit tests: walking back and forward, the saved draft, skipped repeats and blank inputs |
| `shell` keys | Unit tests for every key in section 5.9, with the palette open and closed |
| `shell` commands | Unit tests: every command's output, "No repository yet", usage and unknown-command errors, text without `/` |
| `shell` screen | Mosaic snapshot tests of the prompt with its hint, the open palette, and the filtered palette with highlights |
| `shell` in a pseudo-terminal | Type `/sc`, Tab, a URL, Enter; wait for the report; `/filter tests`; `/skill ` with a Tab-completed skill name; `/quit`; exit `0`; exactly one scan log line |
| `shell` Ctrl-C during a scan | The scan is cancelled with `error: scan interrupted`, the shell keeps running, and `/quit` exits `0` with no scan log line |
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
pseudo-terminal and type keys into it). All three are preinstalled on GitHub-hosted runners.

**Browser tests** are part of the integration tests. They drive the real page in
headless Chromium through Playwright for Java, against a `serve --port 0` process with
the usual stub API and fixture repositories. They wait for page elements, never for a
fixed time. Playwright downloads Chromium on first use into `~/.cache/ms-playwright`
(`~/Library/Caches/ms-playwright` on macOS). CI caches that folder, and on Linux it
installs the browser's system libraries first with Playwright's `install-deps` command.

## 12. Definition of done

Every pull request follows `.github/pull_request_template.md`. CI's **Demos** job (section
13) must be green too. A pull request that changes what users see updates the demo test
cases (13.3) and, through the Update screenshots workflow, the baselines (13.5).


Every change goes through a pull request, managed with the GitHub CLI (`gh`). A change is
done only when **all** of the following are true:

1. **`./gradlew build` passes locally.** This includes every unit test and every CLI
   integration test. No test may be skipped or disabled to make it pass.
2. **The branch is pushed and has a pull request,** opened with
   `gh pr create --base main --fill` or with an explicit title and body.
3. **CI is green on the pull request** for its latest commit, on every platform in the
   matrix, and in the **Demos** job with its screenshot comparison. Watch it with
   `gh pr checks <pr> --watch`.
4. **The pull request is merged** with `gh pr merge <pr> --merge --delete-branch`.

If CI on the pull request is red:

1. Find the failing run, e.g. `gh pr checks <pr>` or `gh run list --branch <branch>`.
2. Read the failing job's logs with `gh run view <run-id> --log-failed`. If the test
   reports are needed, download them with `gh run download <run-id>`.
3. Fix the root cause. Don't skip, disable, or loosen the failing test.
4. Confirm `./gradlew build` passes locally, then commit and push to the same branch.
5. Repeat until CI is green, then merge.

### 12.1 Parallel features

Independent features may be built at the same time, **one feature per sandbox**. Each
sandbox is an isolated copy of the repository with its own branch and its own agent.

1. Each feature gets its own branch (`feature/<name>`) and its own pull request. Each
   must meet the definition of done above on its own.
2. The first pull request to go green is merged.
3. The next one is then brought up to date with `main` (`git merge origin/main`), with
   any conflicts resolved, and it must go green again before it is merged. A pull
   request that was green before `main` changed is not done.
4. After the last merge, CI on `main` must be green.

## 13. Demo recordings and screenshot tests

Every feature has a **demo test**: a deterministic scenario that runs on CI. Each run
records a narrated video of the scenario and takes screenshots at named **key moments**.
Those screenshots are compared pixel by pixel with committed **baselines**. The videos
show the project's current state, and the screenshot comparison catches visual
regressions.

Demos are recorded **only on CI**. There is no local recording path, so a published video
always comes from a clean, pinned environment.

### 13.1 Determinism

A demo test must give the same pixels on every run.

| What | How it is pinned |
|------|------------------|
| Data | The stub GitHub API and fixture repositories from section 11.2, never live GitHub. The fixtures (`DemoFixtures`) model real cases under neutral names (section 13.3): `acme/agent-skills` has ordinary skills, `acme/workbench` has copies in `.agents` and `.claude` plus product copies, and `acme/agent-framework` has test fixtures. Git dates are fixed, so commit SHAs never change. |
| Web browser | Playwright for Java, at the Chromium version its pinned release ships. Viewport 1280×800, device scale factor 1, light color scheme, locale `en-US`, time zone `UTC`. Animations disabled, and the text caret hidden in screenshots. |
| Fonts | Inter (text) and JetBrains Mono (code), both SIL OFL, are committed in `src/integrationTest/resources/fonts/`. Web tests inject them with an `@font-face` style that overrides the page fonts (Playwright's `bypassCSP` allows this in tests only). Terminal tapes use `Set FontFamily "JetBrains Mono"`, installed from the same files. |
| Terminal | VHS, with `ttyd` and `ffmpeg`, at pinned versions. 1400×820, font size 16, `Set Framerate 20`, and a fixed theme. A key moment that follows a command waits for the command's output with `Wait+Screen /…/`
before `Screenshot`. A moment that follows only an in-process key, such as Tab completion,
uses a fixed pause instead, because VHS can't read an interactive program's live input line.
Every `Screenshot` is followed by `Sleep 1s`, because VHS captures it asynchronously. |
| Operating system | Ubuntu, the CI runner. Font rendering differs between operating systems, so baselines are produced and compared on Linux only. |

Nothing time-dependent appears in a key moment. The scan-counter status line, spinners,
and scan-log timestamps are recorded in the video but never screenshotted.

### 13.2 Running

- **Gradle:** `./gradlew demoTest` runs every test tagged `demo` in the integration test
  source set. `integrationTest` excludes that tag, so `./gradlew build` stays fast and
  runs on any machine.
- **CI:** `demoTest` runs only in its own CI job, **Demos**, on `ubuntu-latest`, for every
  push and pull request. The job:
  1. installs the pinned VHS, `ttyd`, `ffmpeg`, the fonts, and Kokoro (section 13.6), with
     the model cached
  2. runs `./gradlew demoTest`
  3. turns each recording into a narrated `.mp4` and a captioned `.gif`
     (`demo/lib/finish.mjs`)
  4. uploads everything as the **`demos`** artifact: videos, GIFs, the screenshots taken,
     and any diff images
- **Output:** each demo writes to `build/demo/<demo>/`:
  - `web.webm` or `terminal.webm`, the raw recording
  - `*.narration.json`
  - `screenshots/<moment>.png`
  - after a failed comparison, `diff/<moment>.png` and `expected/<moment>.png` as well
- **Failing:** a screenshot that differs from its baseline fails `demoTest`, and with it
  the **Demos** job.

### 13.3 Test cases

These are the demos and their key moments. Each key moment is one baseline:
`src/integrationTest/baselines/<demo>/<moment>.png`. Every row says what the screenshot
proves. The spec section and acceptance criterion it covers are in brackets.

**Web view (Playwright): `web-basics`** on `acme/agent-skills`

| Moment | Step | Proves |
|--------|------|--------|
| `01-empty` | Open the page | The form, and the empty state (5.4) |
| `02-scanned` | Scan `github.com/acme/agent-skills` | The summary with name, description, commit and branch; the skill count; the list; the first skill selected (5.4, AC 11) |
| `03-selected` | Click `pdf-toolkit` | Selection, the full description, paths, GitHub link, similar skills, and the rendered Markdown (5.4, AC 13) |
| `04-raw` | Click Raw | The exact file text (5.4) |
| `05-divider` | Drag the divider 160 px to the right | Resizing (5.4) |
| `06-similar-opened` | Click the first similar skill | Similar skills navigate (5.6, AC 15) |

**Web view: `web-search`** on `acme/workbench`

| Moment | Step | Proves |
|--------|------|--------|
| `01-merged` | Scan `github.com/acme/workbench` | Copies merged into one entry each, and the `◆` and `⧉` icons (4.4, AC 12) |
| `02-shipped` | Select `generator` | `shipped in product` and the `also in` paths (4.4) |
| `03-filtered` | Type `test` | Matches, highlighting, and the `n of total` count (5.5, AC 14) |
| `04-snippet` | Scroll to the item whose match is past its shortened description | A snippet (5.5) |
| `05-no-match` | Type `zzz` instead | The no-match message, with nothing selected (5.5) |
| `06-restored` | Esc, type `generator`, then reload | Esc restores the selection; the URL restores the filter and selection (5.4, 5.5) |

**Web view: `web-multi`** on all three fixture repositories

| Moment | Step | Proves |
|--------|------|--------|
| `01-three-repositories` | Enter the three URLs, then Scan | Chips, the summary table, and grouped lists (5.10, AC 24) |
| `02-search-across` | Type `test` | A search across repositories, with group counts (5.10) |
| `03-repo-qualifier` | Replace it with `repo:framework` | The `repo:` qualifier (5.10) |
| `04-ignored` | Scroll to the ignored fixtures | Test fixtures are set aside, with repository prefixes (4.4, 5.10) |
| `05-cross-similar` | Select `split-platform-code` | Similar skills from other repositories, labeled with their repository (5.10) |
| `06-chip-removed` | Remove the `acme/agent-skills` chip | Its group is dropped; the others remain (5.10) |

**Terminal (VHS): `cli`**

| Moment | Step | Proves |
|--------|------|--------|
| `01-rich` | `scan github.com/acme/agent-skills --filter pdf` | The rich view, with highlights (5.1, 5.7) |
| `02-plain` | The same without a filter, piped to `head` | Plain text, with no escape codes (5.2) |
| `03-merged` | `scan github.com/acme/workbench` | Merged copies and product labels (4.4) |
| `04-fixtures` | `scan github.com/acme/agent-framework` | Ignored test fixtures (4.4) |
| `05-skill` | `scan … --skill pdf-toolkit` | The skill detail, with similar-skill bars (5.7, AC 17) |
| `06-several` | Two repositories with `--filter 'repo:framework platform'` | Several repositories and the summary (5.10, AC 23) |

**Terminal: `browse`** on `acme/agent-skills`

| Moment | Step | Proves |
|--------|------|--------|
| `01-open` | `browse github.com/acme/agent-skills` | The split layout, the list, and the selected skill (5.8, AC 18) |
| `02-moved` | ↓ ↓ | Selection and the right pane follow (5.8) |
| `03-filtered` | `/pdf`, then Enter | The live filter, the count, and highlights (5.8) |
| `04-similar` | Tab | The similar-skills cursor (5.8) |

**Terminal: `shell`**

| Moment | Step | Proves |
|--------|------|--------|
| `01-prompt` | `skill-atlas` | The prompt and its hint (5.9, AC 19) |
| `02-palette` | `/` | Every command in the palette (5.9, AC 20) |
| `03-narrowed` | `sc` | Ranking and highlighting (5.9) |
| `04-scanned` | Tab, the URL, Enter | `/scan` output in the scrollback (5.9, AC 21) |
| `05-skill-completion` | `/skill pdf`, Tab | Skill-name completion (5.9, AC 21) |

A new feature that users can see adds a demo, or key moments, to this table. It also adds
the matching baselines, in the same pull request.

### 13.4 Comparing screenshots

- **Per pixel:** a pixel differs when any color channel differs by more than **16** (of
  255). That tolerance absorbs anti-aliasing noise.
- **Per moment:** a key moment fails when more than **0.1 %** of its pixels differ, or when
  its size differs from the baseline.
- **On failure** the test writes:
  - the actual image to `screenshots/`
  - the baseline to `expected/`
  - a diff to `diff/`: the actual image faded to 30 %, with differing pixels in solid red
- **The failure message** names the demo, the moment, the share of differing pixels, and
  the three file paths.
- **Missing baselines:** a moment without a baseline fails with
  `no baseline for <demo>/<moment>; run the Update screenshots workflow`.

### 13.5 Updating baselines

Baselines change only when a change to the UI is intended. They are only ever produced on
CI.

- The **Update screenshots** workflow (`.github/workflows/update-screenshots.yml`, started
  by hand for a branch) runs `./gradlew demoTest -PupdateScreenshots`. That writes every
  key moment as its new baseline, instead of comparing.
- The workflow commits the changed baselines to that branch, as `Update screenshot
  baselines`, and then starts CI on the new commit. The baseline images therefore show up
  in the pull request's diff, where reviewers can check them.
- A pull request that changes baselines says why in its **Decisions** section.

### 13.6 Narration

Videos keep the voice-over and captions: Kokoro `af_heart`, an original voice (never an
imitation of a real person), with the caption bar of section 13.1's fonts.

- **Web demo tests** call `narrate("…")`. All of a demo's lines are rendered up front.
  `narrate` notes the line's start time, then waits for its length plus 0.4 s. That keeps
  the voice in sync with the screen, and it never affects screenshots, because key moments
  are taken after narration has finished.
- **Tapes** keep `# say:` comments (`demo/lib/tape-narration.mjs`). A `Wait` counts as 1 s,
  which is about how long a stubbed command takes, mostly the JVM starting.
- **Without Kokoro,** narration lengths are estimated at 2.6 words per second, and the video
  has captions only.

### 13.7 Publishing: only the latest

There is exactly one published set of demos, and it shows the current state of `main`.

- **On `main`:** each run of the **Demos** job replaces `demos/latest/` on the orphan `demos`
  branch with that run's videos and GIFs, in one commit. The commit message names the
  `main` commit that the demos come from.
- **Nothing else is kept.** Folders from older demos (`demos/pr-*`, `demos/tour/`) are
  removed, so nothing stale remains.
- **Pull requests** get their demos as the `demos` artifact of their CI run. The PR
  template's **Demo** section links to that run.
- **README:** its Demos section links to `demos/latest/`.

### 13.8 Proving that it catches regressions

When this is set up, and again whenever the comparison changes, the check is broken on
purpose:
1. A throwaway branch changes the page in a way users would notice, for example hiding the
   similar-skills section.
2. The **Demos** job must fail on that branch. The diff images must show the change in red.
3. The branch is then deleted without being merged.

### 13.9 Skill

The `record-demo` skill (`.agents/skills/record-demo/SKILL.md`, with an identical copy in
`.claude/skills/`) explains how to add a demo or key moments, how to read a failed
comparison, and how to accept an intended change with the Update screenshots workflow.

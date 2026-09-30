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
skill-atlas scan <github-project-url>... [--filter <words>] [--skill <name-or-path>]
skill-atlas browse <github-project-url>
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
19. `scan <url> <url>` reports both repositories, and one failing repository doesn't hide
    the other (section 5.10).
20. The web view loads several repositories, groups their skills, and searches across all
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

Every pull request follows `.github/pull_request_template.md`. A pull request that
changes what users see includes a demo recorded as described in section 13.


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

## 13. Demo recordings

A pull request that changes what users see includes a short demo video. The video comes
from a **script**, not from a hand-made screen recording, so anyone can record it again,
it looks the same every time, and no screen-recording permission is needed.

### 13.1 Tools

| What | Tool | Output |
|------|------|--------|
| Web view | Playwright video recording in headless Chromium, driven by a Node script | `.webm`, converted to `.mp4` and `.gif` with `ffmpeg` |
| Terminal (`scan`, `browse`, shell) | VHS, driven by a `.tape` script | `.gif` and `.mp4` |

`demo/package.json` pins Playwright to the same version as the Java tests. VHS
(`brew install vhs`) brings `ffmpeg` and `ttyd` with it.

### 13.2 Scripts

Everything lives in `demo/`:

- `demo/record.sh <name>` builds the distribution (`./gradlew installDist`). It starts
  `skill-atlas serve` on a free port, runs the recording scripts for `<name>`, and stops
  the server again, including when a step fails. The results go to `build/demo/<name>/`.
- `demo/<name>/web.mjs` is a Playwright script: it opens the web view, types, clicks, and
  pauses on each result long enough to read it (about 1.5 s).
- `demo/<name>/terminal.tape` is a VHS script. It records at 1400×820 px, with the font at
  16 px and a typing speed of 60 ms.
- `demo/publish.sh <pr-number> <name>` publishes the files (section 13.3) and prints the
  Markdown to paste into the pull request.

The demo scripts run against real GitHub repositories, with `GITHUB_TOKEN=$(gh auth token)`,
because a demo shows the real thing. They aren't tests and aren't part of `./gradlew build`.

A demo file must stay small:
- **Web GIF:** at most 30 s, 960 px wide, 10 fps, and under 8 MB.
- **Terminal GIF:** under 5 MB.

The `.mp4` is kept next to the GIF for full quality.

### 13.3 Publishing

Demo files never go into `main`. They live on an **orphan branch** called `demos`, which
has no shared history with `main`, under `demos/pr-<number>/`. `demo/publish.sh`:

1. Checks out `demos` in a temporary worktree, or creates the orphan branch if it doesn't
   exist.
2. Copies `build/demo/<name>/*` into `demos/pr-<number>/`.
3. Commits and pushes `demos`.
4. Prints Markdown for the pull request:

```markdown
![Web demo](https://github.com/<owner>/<repo>/blob/demos/demos/pr-<n>/web.gif?raw=true)
[Full-quality video](https://github.com/<owner>/<repo>/blob/demos/demos/pr-<n>/web.mp4)
```

The repository is private, so these links work for people who have access to it. That's
the same audience as the pull request.

### 13.4 Skill

How to record a demo is also captured as the `record-demo` skill
(`.agents/skills/record-demo/SKILL.md`, with an identical copy in `.claude/skills/`). It
covers a web view change, a terminal change, an interactive terminal view, recording
again after a review, and common failures.


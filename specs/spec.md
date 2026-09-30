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

## 4. Behavior

### 4.1 Processing steps

1. **Parse and validate the URL.** Normalize it to `owner/repo`.
2. **Fetch repository metadata** from the GitHub REST API (`GET /repos/{owner}/{repo}`):
   - `full_name` → repository name
   - `description` → repository description (may be `null`)
   - `default_branch` → the branch that is scanned
3. **Get the repository contents** of the default branch with a shallow clone
   (`git clone --depth 1 --branch <default-branch>`) into a temporary directory.
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

Rules:
- `Description:` shows `(none)` when the repository has no description.
- `Commit:` shows the full SHA, followed by the default branch name in parentheses.
- When no skills are found, the header is followed by `No skills found.`
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

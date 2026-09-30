# Skill Atlas — Specification

## 1. Overview

Skill Atlas is a command-line tool that scans a GitHub repository and reports every
agent skill defined in it. For each skill it lists the skill's **name** and
**description**. Each scan also logs which repository was scanned: its **name**,
**description**, and the **commit** that was read.

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

1. **Parse and validate the URL.** Normalize it to `owner/repo` plus an optional ref.
2. **Fetch repository metadata** from the GitHub REST API (`GET /repos/{owner}/{repo}`):
   - `full_name` → repository name
   - `description` → repository description (may be `null`)
   - `default_branch` → used when no ref is given
3. **Get the repository contents** at the chosen ref with a shallow clone
   (`git clone --depth 1 --branch <ref>`) into a temporary directory. When the ref is
   a commit SHA, fetch that single commit instead.
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

The report goes to **stdout**. Progress messages, warnings, and errors go to
**stderr**, so stdout can be piped or redirected cleanly.

### 5.1 Text format (default)

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
- `Commit:` shows the full SHA, followed by the ref name in parentheses.
- When no skills are found, the header is followed by `No skills found.`
- Long descriptions are not truncated.

## 6. Scan log

Every successful scan is logged, in addition to the report on stdout.

- **Location:** `$XDG_STATE_HOME/skill-atlas/scans.log`, or
  `~/.local/state/skill-atlas/scans.log` when `XDG_STATE_HOME` is not set.
  The directory is created if it doesn't exist.
- **Format:** JSON Lines, one object per scan, appended to the file:

```json
{"scanned_at":"2026-09-30T10:28:00Z","repository":"anthropics/skills","description":"Public repository for Agent Skills","ref":"main","commit":"3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39","skill_count":3}
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
| `4` | Ref not found | `error: ref 'feature-x' not found in owner/repo` |
| `5` | Network or GitHub API failure, including rate limiting | `error: GitHub API rate limit exceeded; set GITHUB_TOKEN to raise the limit` |

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

## 9. Acceptance criteria

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

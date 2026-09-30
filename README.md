# Skill Atlas

Lists the agent skills (`SKILL.md` files) defined in a GitHub repository, together with
the repository's name, description, and the commit that was scanned. See
[`specs/spec.md`](specs/spec.md) for the full specification.

```
skill-atlas scan <github-project-url>
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

`./gradlew distZip` builds a distributable archive in `build/distributions/`.

Every successful scan is appended as one JSON line to
`$XDG_STATE_HOME/skill-atlas/scans.log` (default `~/.local/state/skill-atlas/scans.log`).

## Tests

```
./gradlew test
```

The tests use a local git repository and a stubbed GitHub API, so they need no network access.

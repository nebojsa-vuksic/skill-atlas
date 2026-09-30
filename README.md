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
./gradlew build
```

This runs the unit tests (`./gradlew test`) and the CLI integration tests
(`./gradlew integrationTest`). The integration tests run the installed `skill-atlas`
launcher as a separate process against local fixture repositories and a stub GitHub API,
including one run inside a pseudo-terminal. They need `git` and `python3`, but no
network access.

CI runs the same build on Linux and macOS for every push and pull request. See the
definition of done in [`specs/spec.md`](specs/spec.md#12-definition-of-done): every
change goes through a pull request and is merged once CI is green.

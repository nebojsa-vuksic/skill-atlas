# Plan: scan an organization's repositories

Implements spec section 5.12 (with 3.3, 7, 10.25–28 and the scenarios in 11.2). The spec
says what the feature does; this file says how we build it and in which order. Each step
ends with its tests passing, so the branch stays green while it grows.

## Approach

An owner URL expands into a list of repositories before anything is cloned. From there on,
the existing several-repositories pipeline (section 5.10) does the work: scanning up to 4
at a time, one cross-repository similarity pass, the plain and rich reports, `/api/scans`,
and the grouped web view.

```
owner URL ──▶ GET /users/{owner}            organization or user
          ──▶ GET /orgs|users/{owner}/repos  paged, 100 at a time
          ──▶ drop forks and archived        counted as skipped
          ──▶ GET …/git/trees/{branch}       up to 8 at a time; keep trees with SKILL.md or truncated
          ──▶ MultiScanner                   clone and scan the survivors, like section 5.10
```

The tree check is what makes this usable: JetBrains has 865 repositories, and cloning them
all would take the better part of an hour. GitHub code search was considered and rejected:
it needs a token, allows 10 requests a minute, returns at most 1,000 results, and its
results for `filename:SKILL.md org:JetBrains` missed repositories that have skills.

## Steps

1. **Spec.** Section 5.12, the owner URL forms in 3.3, the new messages in 7, acceptance
   criteria 25–28, and the test scenarios in 11.2. *(This change.)*
2. **URL parsing** (`GitHubUrl.kt`). Add `GitHubUrl.target(input)`, returning a repository
   or an owner (`ScanTarget`). `parse` keeps rejecting owner URLs, so every single-repository
   caller is unchanged. Unit tests: every owner form, `orgs/<name>`, and that `parse` still
   rejects them.
3. **GitHub client** (`GitHubClient.kt`). Share one request helper between the existing
   metadata call and three new ones: `fetchOwner`, `listRepositories` (paged until a short
   page) and `checkSkillFiles` (the tree answers of 5.12 step 4). Errors map onto the
   existing exceptions, plus `OwnerNotFoundException` (exit 3).
4. **Discovery** (`OwnerSearch.kt`). `OwnerSearch.discover(owner)`: list, skip, check up to
   8 at a time, sort. Returns the repositories to scan with their listed metadata, and the
   counts for the `Searched` line. Unit tests against an in-process stub of the client.
5. **Scanner.** `Scanner.scan(url)` rejects owner URLs with `OwnerUrlNotSupportedException`
   (exit 2), which covers `browse`, the shell and `/api/scan` at once. A second entry
   point scans a listed repository without fetching its metadata again.
6. **MultiScanner.** Expand owners in place, keep the first position of a repository given
   twice, scan everything with the existing pool, and return the owners' outcomes next to
   the repositories' (`MultiScan`). An owner's repositories without skills stay in the
   result, so they are logged and cached, but are marked so the views can leave them out.
7. **Reports** (`Presentation.kt`, `Report.kt`, `RichScanView.kt`). `MultiList` leaves out
   the owners' repositories without skills, adds the `Searched` lines before the summary,
   and counts failed owners. `scan` uses `MultiList` whenever an owner URL is given.
8. **CLI** (`Main.kt`). Owner failures print `error: <owner>: …` in URL order and take part
   in the first-failure exit code. Integration tests with stub owners and fixture
   repositories, in a new `OwnerIntegrationTest`.
9. **Test harness** (`Sandbox.kt`). The stub API learns owners, paged repository lists,
   trees (from the fixture files), empty and truncated trees, and records requests.
10. **Web API** (`WebServer.kt`). Accept owner URLs in `/api/scans`, add `owners` and
    `from`, cache owner discoveries for 10 minutes next to `ScanCache`, and answer
    `/api/scan` with an owner URL with `400`.
11. **Web page** (`app.js`, `style.css`). Owner chips, the `Searched` lines under the
    summary table, `repositoryKey` for owner URLs, and the single-failed-owner error.
    Browser test in `OwnerIntegrationTest`.
12. **Real runs.** `scan https://github.com/anthropics`, `scan https://github.com/JetBrains`
    and a user account, with `GITHUB_TOKEN`; the web view with an owner chip. Note the
    timings in the PR.
13. **Wrap-up.** Shared memory, the PR from the template, a demo of the owner chip and
    the CLI output (spec section 13), CI green on the latest commit, merge, CI green on
    `main`.

## Out of scope

- Options to include forks or archived repositories. They can come later as flags; the
  `Searched` line already says how many were skipped.
- Owners in `browse` and the shell, which stay single-repository (section 5.10).
- Enterprise GitHub hosts: owner URLs follow the same host rules as repository URLs.
- A user's private repositories. `/users/{user}/repos` lists public ones only; the token's
  own private repositories would need `/user/repos`.

## Risks

- **Rate limits.** About one request per repository. With a token that is 5,000 an hour,
  plenty for JetBrains' 865; without one it is 60, so owner scans fail fast with the
  existing rate-limit message and exit `5`.
- **Huge trees.** `JetBrains/kotlin`'s recursive tree is truncated and several MB. A
  truncated tree means "clone it", so correctness doesn't depend on the tree being whole.

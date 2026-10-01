package skillatlas.it

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir

/** Every repository of an organization or user, in `scan`, `/api/scans` and the web view (spec section 5.11). */
class OwnerIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var big: String
    private lateinit var one: String
    private lateinit var two: String

    private fun skill(name: String, description: String, body: String = "# $name\n") =
        "---\nname: $name\ndescription: $description\n---\n\n$body"

    /** A fixture repository whose tree the stub API serves too, as GitHub would. */
    private fun repository(fullName: String, files: Map<String, String>, truncated: Boolean = false): String {
        sandbox.api.tree(fullName, if (truncated) emptyList() else files.keys, truncated)
        return sandbox.createRepository(fullName, files)
    }

    /**
     * The organization `acme`, listed out of order: three repositories with skills (one behind a
     * truncated tree), one with only a test fixture, two that must never be cloned (no `SKILL.md`,
     * and empty), a fork and an archived repository. Only those with skills or a truncated tree
     * exist as git repositories, so cloning any other would fail the scan.
     */
    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        sandbox.api.owner(
            "acme",
            listOf(
                StubGitHubApi.Listed("two", "Other tools"),
                StubGitHubApi.Listed("one", "Acme tools"),
                StubGitHubApi.Listed("fork", "A fork", fork = true),
                StubGitHubApi.Listed("Big", "Big monorepo"),
                StubGitHubApi.Listed("docs", "Documentation"),
                StubGitHubApi.Listed("empty", null),
                StubGitHubApi.Listed("old", "Archived", archived = true),
                StubGitHubApi.Listed("fixtures", "Fixtures only"),
            ),
        )
        one = repository(
            "acme/one",
            mapOf(
                "skills/pdf/SKILL.md" to skill("pdf-extract", "Extract text and tables from PDF files.", "# PDF\n\nUse pdftotext.\n"),
                "skills/commits/SKILL.md" to skill("commits", "How to write commit messages."),
            ),
        )
        two = repository(
            "acme/two",
            mapOf("skills/pdf/SKILL.md" to skill("pdf-forms", "Fill PDF forms and extract fields from PDF files.")),
        )
        big = repository("acme/Big", mapOf("tools/big/SKILL.md" to skill("big-skill", "Handle the big monorepo.")), truncated = true)
        repository("acme/fixtures", mapOf("src/test/resources/skills/demo/SKILL.md" to skill("demo", "A test fixture.")))
        sandbox.api.tree("acme/docs", listOf("README.md", "docs/guide.md"))
        sandbox.api.emptyTree("acme/empty")
    }

    @AfterTest
    fun tearDown() = sandbox.close()

    private fun header(name: String, description: String, commit: String) =
        "Repository:  $name\nDescription: $description\nCommit:      $commit (main)\n\n"

    private val separator = "\n${"─".repeat(80)}\n\n"

    private val acmeReport: String
        get() = header("acme/Big", "Big monorepo", big) + """
            Found 1 skill:

              big-skill
                Handle the big monorepo.
                tools/big

        """.trimIndent() + separator + header("acme/one", "Acme tools", one) + """
            Found 2 skills:

              commits
                How to write commit messages.
                skills/commits

              pdf-extract
                Extract text and tables from PDF files.
                skills/pdf

        """.trimIndent() + separator + header("acme/two", "Other tools", two) + """
            Found 1 skill:

              pdf-forms
                Fill PDF forms and extract fields from PDF files.
                skills/pdf

        """.trimIndent()

    private fun repositoriesIn(report: String) = Regex("^Repository: +(.+)$", RegexOption.MULTILINE).findAll(report).map { it.groupValues[1] }.toList()

    // ---- CLI ----

    @Test
    fun `scan of an organization reports its repositories with skills`() {
        val run = sandbox.run("scan", "https://github.com/acme")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            acmeReport + "\n" + """
                Searched acme: 3 of 6 repositories have skills (2 forks or archived skipped)
                Scanned 3 repositories: 4 skills

            """.trimIndent(),
            run.stdout,
        )

        val progress = run.stderr.lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf("Listing repositories of acme...", "Checking 6 repositories of acme for skill files..."),
            progress.take(2),
            run.stderr,
        )
        assertEquals(
            listOf("Cloning acme/Big (main)...", "Cloning acme/fixtures (main)...", "Cloning acme/one (main)...", "Cloning acme/two (main)..."),
            progress.drop(2).sorted(),
            "only repositories with SKILL.md in their tree, or a truncated tree, are cloned; in parallel, so in any order",
        )

        // The fixture-only repository was scanned, so it is logged although it isn't reported.
        val logged = sandbox.scanLogLines().map { Regex(""""repository":"([^"]+)"""").find(it)!!.groupValues[1] }.sorted()
        assertEquals(listOf("acme/Big", "acme/fixtures", "acme/one", "acme/two"), logged)
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())

        val requests = sandbox.api.requests()
        assertTrue(requests.none { "/repos/acme/fork" in it || "/repos/acme/old" in it }, "forks and archived repositories are never checked: $requests")
        assertTrue("/repos/acme/one" !in requests, "the listing already has the metadata: $requests")
        assertEquals(1, requests.count { it.startsWith("/orgs/acme/repos?") }, requests.toString())
    }

    @Test
    fun `scan of a user lists the user's own repositories`() {
        sandbox.api.owner("jane", listOf(StubGitHubApi.Listed("notes", "Jane's notes")), type = "User")
        val notes = repository("jane/notes", mapOf("SKILL.md" to skill("notes", "Take notes.")))

        val run = sandbox.run("scan", "github.com/jane/")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            header("jane/notes", "Jane's notes", notes) + """
                Found 1 skill:

                  notes
                    Take notes.
                    .

                Searched jane: 1 of 1 repository has skills
                Scanned 1 repository: 1 skill

            """.trimIndent(),
            run.stdout,
        )
        assertTrue("/users/jane/repos?type=owner&sort=full_name&per_page=100&page=1" in sandbox.api.requests(), sandbox.api.requests().toString())
    }

    @Test
    fun `an owner with more than 100 repositories is listed page by page`() {
        val archived = (0 until 100).map { StubGitHubApi.Listed("r%03d".format(it), archived = true) }
        sandbox.api.owner("many", archived + StubGitHubApi.Listed("zeta", "The last one"))
        repository("many/zeta", mapOf("skills/z/SKILL.md" to skill("zeta", "The last skill.")))

        val run = sandbox.run("scan", "github.com/orgs/many")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(
            run.stdout.endsWith("Searched many: 1 of 1 repository has skills (100 forks or archived skipped)\nScanned 1 repository: 1 skill\n"),
            run.stdout,
        )
        val pages = sandbox.api.requests().filter { it.startsWith("/orgs/many/repos?") }.map { it.substringAfterLast("page=") }
        assertEquals(listOf("1", "2"), pages, "page 2 is short, so there is no page 3")
    }

    @Test
    fun `owners and repositories mix, and a repository is scanned once`() {
        sandbox.api.repository("acme/two", "Other tools")
        sandbox.api.repository("other/solo", "Solo tools")
        repository("other/solo", mapOf("skills/pdf/SKILL.md" to skill("pdf-merge", "Merge PDF files into one PDF.")))

        val run = sandbox.run("scan", "github.com/acme/two", "https://github.com/orgs/acme/repositories", "github.com/other/solo")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(listOf("acme/two", "acme/Big", "acme/one", "other/solo"), repositoriesIn(run.stdout))
        assertTrue(
            run.stdout.endsWith(
                "\nSearched acme: 3 of 6 repositories have skills (2 forks or archived skipped)\nScanned 4 repositories: 5 skills\n",
            ),
            "acme/two counts for acme although it is reported under its own URL:\n${run.stdout}",
        )
        assertEquals(5, sandbox.scanLogLines().size, "acme/two is scanned once")

        // Similar skills come from every repository, wherever it was found.
        val detail = sandbox.run("scan", "github.com/acme", "github.com/other/solo", "--skill", "other/solo:skills/pdf")
        assertEquals(0, detail.exitCode, detail.toString())
        assertTrue(Regex("""\n  pdf-extract +█*░* +\d+ %  acme/one:skills/pdf\n""").containsMatchIn(detail.stdout), detail.stdout)
    }

    @Test
    fun `an unknown owner fails without hiding the other repositories`() {
        sandbox.api.repository("acme/two", "Other tools")
        val run = sandbox.run("scan", "github.com/nobody", "github.com/acme/two")

        assertEquals(3, run.exitCode, run.toString())
        assertEquals(
            header("acme/two", "Other tools", two) + """
                Found 1 skill:

                  pdf-forms
                    Fill PDF forms and extract fields from PDF files.
                    skills/pdf

                Scanned 1 repository: 1 skill, 1 failed

            """.trimIndent(),
            run.stdout,
        )
        assertTrue(run.stderr.endsWith("error: nobody: organization or user nobody not found\n"), run.stderr)
    }

    @Test
    fun `a rate limit while checking trees fails the whole owner`() {
        sandbox.api.owner("limited", listOf(StubGitHubApi.Listed("a"), StubGitHubApi.Listed("b")))
        sandbox.api.rateLimitedTree("limited/a")
        sandbox.api.tree("limited/b", listOf("SKILL.md"))

        val run = sandbox.run("scan", "github.com/limited")

        assertEquals(5, run.exitCode, run.toString())
        assertEquals("Scanned 0 repositories: 0 skills, 1 failed\n", run.stdout)
        assertTrue(run.stderr.endsWith("error: limited: GitHub API rate limit exceeded; set GITHUB_TOKEN to raise the limit\n"), run.stderr)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `an owner without skills reports only what was searched`() {
        sandbox.api.owner("quiet", listOf(StubGitHubApi.Listed("docs")))
        sandbox.api.tree("quiet/docs", listOf("README.md"))

        val run = sandbox.run("scan", "github.com/quiet")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("Searched quiet: 0 of 1 repository has skills\nScanned 0 repositories: 0 skills\n", run.stdout)
    }

    @Test
    fun `scan of an organization in a terminal shows the rich view`() {
        val run = sandbox.runInTerminal("scan", "github.com/acme")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue("\u001B[" in run.stdout, "the rich view should use escape codes")
        val screen = stripEscapeCodes(run.stdout).lines().joinToString("\n") { it.trimEnd() }
        assertTrue(
            "\n Searched acme: 3 of 6 repositories have skills (2 forks or archived skipped)\n Scanned 3 repositories: 4 skills\n" in screen,
            screen,
        )
        assertTrue(" Repository   acme/Big\n" in screen, screen)
    }

    @Test
    fun `commands that work on one repository reject an owner URL`() {
        val message = "github.com/acme names an organization or user, not a repository; " +
            "use \"skill-atlas scan github.com/acme\" to search its repositories"

        val browse = sandbox.runInTerminal("browse", "github.com/acme")
        assertEquals(2, browse.exitCode, browse.toString())
        assertTrue("error: $message" in stripEscapeCodes(browse.stdout), browse.stdout)

        sandbox.startWebView().use { web ->
            val response = web.get("/api/scan?url=github.com/acme")
            assertEquals(400, response.status, response.toString())
            assertEquals("""{"error":${jsonString(message)},"exit_code":2}""", response.body)
        }
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    // ---- /api/scans ----

    @Test
    fun `the API expands owners, keeps a failed owner in its row, and caches owners`() {
        sandbox.startWebView().use { web ->
            val response = web.get("/api/scans?url=github.com/acme&url=github.com/nobody")

            assertEquals(200, response.status, response.toString())
            val body = response.body
            assertTrue(
                body.startsWith(
                    """{"repositories":[""" +
                        """{"url":"https://github.com/acme/Big","from":"github.com/acme","name":"acme/Big","description":"Big monorepo","branch":"main","commit":"$big","skill_count":1},""" +
                        """{"url":"https://github.com/acme/one","from":"github.com/acme","name":"acme/one","description":"Acme tools","branch":"main","commit":"$one","skill_count":2},""" +
                        """{"url":"https://github.com/acme/two","from":"github.com/acme","name":"acme/two","description":"Other tools","branch":"main","commit":"$two","skill_count":1}],""" +
                        """"owners":[""" +
                        """{"url":"github.com/acme","name":"acme","type":"Organization","repository_count":6,"skipped":2,""" +
                        """"repositories":["acme/Big","acme/one","acme/two"],""" +
                        """"summary":"Searched acme: 3 of 6 repositories have skills (2 forks or archived skipped)"},""" +
                        """{"url":"github.com/nobody","name":"nobody","error":"organization or user nobody not found","exit_code":3}],""" +
                        """"skills":[""",
                ),
                body,
            )
            assertTrue(""""repository":"acme/two","id":"acme/two:skills/pdf","name":"pdf-forms"""" in body, body)
            assertEquals(4, sandbox.scanLogLines().size, "every scanned repository is logged, the fixture-only one too")

            // Within 10 minutes the same owner loads from the cache: no GitHub request, no clone, no log line.
            val before = sandbox.api.requests().size
            val again = web.get("/api/scans?url=https://github.com/ACME")
            assertEquals(200, again.status, again.toString())
            assertTrue(""""name":"acme/Big"""" in again.body, again.body)
            assertEquals(before, sandbox.api.requests().size, sandbox.api.requests().drop(before).toString())
            assertEquals(4, sandbox.scanLogLines().size)
        }
    }

    // ---- Web view ----

    @Test
    fun `the web view shows an owner as one chip with its repositories`() {
        sandbox.api.repository("other/solo", "Solo tools")
        repository("other/solo", mapOf("skills/pdf/SKILL.md" to skill("pdf-merge", "Merge PDF files into one PDF.")))

        sandbox.startWebView().use { web ->
            val page = browser.newPage()
            page.navigate(web.url)
            page.fill("#url", "github.com/acme github.com/other/solo")
            page.click("#scan-button")
            page.waitForSelector("body[data-state=idle]")

            assertThat(page.locator(".chip")).hasCount(2)
            val owner = page.locator(".chip.owner")
            assertThat(owner.locator(".chip-name")).hasText("acme")
            assertThat(owner.locator(".chip-repos")).hasText("3 repos")
            assertThat(owner.locator(".chip-count")).hasText("4")
            assertThat(owner).hasAttribute("title", "Searched acme: 3 of 6 repositories have skills (2 forks or archived skipped)")
            assertThat(page.locator(".group-header .group-name")).hasText(arrayOf("acme/Big", "acme/one", "acme/two", "other/solo"))
            assertThat(page.locator("#repo-rows tr")).hasCount(4)
            assertThat(page.locator("#owner-lines li")).hasText(arrayOf("Searched acme: 3 of 6 repositories have skills (2 forks or archived skipped)"))
            assertThat(page.locator("#filter-count")).hasText("5 of 5")

            page.fill("#filter", "repo:acme pdf")
            assertThat(page.locator("#filter-count")).hasText("2 of 5")

            // The URL keeps the owner URL, and restores the same search.
            val url = page.url()
            assertTrue("url=github.com%2Facme&" in url, url)
            val restored = browser.newPage()
            restored.navigate(url)
            restored.waitForSelector("body[data-state=idle]")
            assertThat(restored.locator(".chip.owner .chip-name")).hasText("acme")
            assertThat(restored.locator("#filter-count")).hasText("2 of 5")

            // Removing the owner's chip removes its repositories; one repository left looks as it did before.
            restored.locator(".chip.owner .chip-remove").click()
            restored.waitForSelector("body[data-state=idle]")
            assertThat(restored.locator(".chip")).hasCount(1)
            assertThat(restored.locator(".group-header")).hasCount(0)
            assertThat(restored.locator("#owner-lines")).isHidden()
            assertThat(restored.locator("#repo-name")).hasText("other/solo")
            restored.close()
            page.close()
        }
    }

    @Test
    fun `the web view shows a failed owner like a failed repository`() {
        sandbox.startWebView().use { web ->
            val page = browser.newPage()
            page.navigate(web.url + "?url=github.com/nobody")
            page.waitForSelector("body[data-state=idle]")

            assertThat(page.locator("#error")).hasText("error: organization or user nobody not found")
            assertThat(page.locator("#result")).isHidden()
            assertThat(page.locator(".chip.owner.failed .chip-count")).hasText("failed")
            page.close()
        }
    }

    companion object {
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        private val ESCAPE_CODES = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(\u0007|\u001B\\)|\u001B[=>78]""")

        fun stripEscapeCodes(text: String) = text.replace(ESCAPE_CODES, "").replace("\r", "")

        fun jsonString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        @JvmStatic
        @BeforeAll
        fun launchBrowser() {
            playwright = Playwright.create()
            browser = playwright.chromium().launch()
        }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            browser.close()
            playwright.close()
        }
    }
}

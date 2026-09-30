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

/** Several repositories in `scan`, `/api/scans` and the web view (spec section 5.10). */
class MultiRepositoryIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var one: String
    private lateinit var two: String

    private fun skill(name: String, description: String, body: String) = "---\nname: $name\ndescription: $description\n---\n\n$body"

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        sandbox.api.repository("acme/one", "Acme tools")
        sandbox.api.repository("acme/two", "Other tools")
        one = sandbox.createRepository(
            "acme/one",
            mapOf(
                "skills/pdf/SKILL.md" to skill("pdf-extract", "Extract text and tables from PDF files.", "# PDF\n\nUse pdftotext.\n"),
                "skills/commits/SKILL.md" to skill("commits", "How to write commit messages.", "# Commits\n"),
            ),
        )
        two = sandbox.createRepository(
            "acme/two",
            mapOf("skills/pdf/SKILL.md" to skill("pdf-forms", "Fill PDF forms and extract fields from PDF files.", "# Forms\n")),
        )
    }

    @AfterTest
    fun tearDown() = sandbox.close()

    private fun header(name: String, description: String, commit: String) =
        "Repository:  $name\nDescription: $description\nCommit:      $commit (main)\n\n"

    // ---- CLI ----

    @Test
    fun `scan reports several repositories with a summary`() {
        val run = sandbox.run("scan", "github.com/acme/one", "https://github.com/acme/two", "git@github.com:acme/one.git")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            header("acme/one", "Acme tools", one) + """
                Found 2 skills:

                  commits
                    How to write commit messages.
                    skills/commits

                  pdf-extract
                    Extract text and tables from PDF files.
                    skills/pdf

                ${"─".repeat(80)}

            """.trimIndent() + "\n" + header("acme/two", "Other tools", two) + """
                Found 1 skill:

                  pdf-forms
                    Fill PDF forms and extract fields from PDF files.
                    skills/pdf

                Scanned 2 repositories: 3 skills

            """.trimIndent(),
            run.stdout,
        )
        assertEquals(2, sandbox.scanLogLines().size, "one line per repository; the duplicate URL is scanned once")
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `scan keeps reporting when one repository fails`() {
        val run = sandbox.run("scan", "github.com/acme/missing", "github.com/acme/two", "--filter", "pdf")

        assertEquals(3, run.exitCode, run.toString())
        assertEquals(
            header("acme/two", "Other tools", two) + """
                Found 1 skill, 1 match "pdf":

                  pdf-forms
                    Fill PDF forms and extract fields from PDF files.
                    skills/pdf

                Scanned 1 repository: 1 of 1 skills match "pdf", 1 failed

            """.trimIndent(),
            run.stdout,
        )
        assertTrue(
            run.stderr.endsWith("error: acme/missing: repository acme/missing not found (is it private? set GITHUB_TOKEN)\n"),
            run.stderr,
        )
        assertEquals(1, sandbox.scanLogLines().size)
    }

    @Test
    fun `scan --skill looks across repositories`() {
        val run = sandbox.run("scan", "github.com/acme/one", "github.com/acme/two", "--skill", "acme/two:skills/pdf")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(run.stdout.startsWith(header("acme/two", "Other tools", two) + "Skill:       pdf-forms\n"), run.stdout)
        assertTrue(Regex("""\n  pdf-extract +█*░* +\d+ %  acme/one:skills/pdf\n""").containsMatchIn(run.stdout), run.stdout)

        val ambiguous = sandbox.run("scan", "github.com/acme/one", "github.com/acme/two", "--skill", "skills/pdf")
        assertEquals(2, ambiguous.exitCode, ambiguous.toString())
        assertTrue(
            ambiguous.stderr.endsWith(
                "error: skill name 'skills/pdf' matches 2 skills: acme/one:skills/pdf, acme/two:skills/pdf; pass a path instead\n",
            ),
            ambiguous.stderr,
        )
    }

    // ---- /api/scans ----

    @Test
    fun `the API combines repositories, keeps failures in their rows, and caches results`() {
        sandbox.startWebView().use { web ->
            val response = web.get("/api/scans?url=github.com/acme/one&url=github.com/acme/missing&url=github.com/acme/two")

            assertEquals(200, response.status, response.toString())
            val body = response.body
            assertTrue(
                body.startsWith(
                    """{"repositories":[""" +
                        """{"url":"github.com/acme/one","name":"acme/one","description":"Acme tools","branch":"main","commit":"$one","skill_count":2},""" +
                        """{"url":"github.com/acme/missing","name":"acme/missing","error":"repository acme/missing not found (is it private? set GITHUB_TOKEN)","exit_code":3},""" +
                        """{"url":"github.com/acme/two","name":"acme/two","description":"Other tools","branch":"main","commit":"$two","skill_count":1}],""",
                ),
                body,
            )
            assertTrue(""""repository":"acme/two","id":"acme/two:skills/pdf","name":"pdf-forms"""" in body, body)
            assertTrue(Regex(""""similar":\[\{"repository":"acme/one","id":"acme/one:skills/pdf","path":"skills/pdf","name":"pdf-extract","score":\d+\}""").containsMatchIn(body), body)
            assertEquals(2, sandbox.scanLogLines().size)

            // A second request reuses the cached results: no new clone, no new log line.
            assertEquals(200, web.get("/api/scans?url=github.com/acme/one&url=github.com/acme/two").status)
            assertEquals(2, sandbox.scanLogLines().size)

            assertEquals(400, web.get("/api/scans").status)
            val tooMany = web.get("/api/scans?" + (1..11).joinToString("&") { "url=github.com/acme/r$it" })
            assertEquals(400, tooMany.status)
            assertEquals("""{"error":"pass between 1 and 10 url query parameters","exit_code":2}""", tooMany.body)
        }
    }

    // ---- Web view ----

    @Test
    fun `the web view loads several repositories, groups them and searches across them`() {
        sandbox.startWebView().use { web ->
            val page = browser.newPage()
            page.navigate(web.url)
            page.fill("#url", "github.com/acme/one, github.com/acme/two")
            page.click("#scan-button")
            page.waitForSelector("body[data-state=idle]")

            assertThat(page.locator(".chip")).hasCount(2)
            assertThat(page.locator(".chip .chip-name")).hasText(arrayOf("acme/one", "acme/two"))
            assertThat(page.locator(".group-header .group-name")).hasText(arrayOf("acme/one", "acme/two"))
            assertThat(page.locator("#repo-table")).isVisible()
            assertThat(page.locator("#filter-count")).hasText("3 of 3")

            page.fill("#filter", "repo:two pdf")
            assertThat(page.locator("#filter-count")).hasText("1 of 3")
            assertThat(page.locator(".group-header:visible")).hasCount(1)
            assertThat(page.locator("#detail-repo")).hasText("acme/two")
            assertThat(page.locator("#detail-name")).hasText("pdf-forms")

            // Its most similar skill lives in the other repository; opening it clears the filter that hides it.
            val other = page.locator(".similar-row", Page.LocatorOptions().setHasText("pdf-extract"))
            assertThat(other.locator(".similar-repo")).hasText("acme/one · ")
            other.click()
            assertThat(page.locator("#filter")).hasValue("")
            assertThat(page.locator("#detail-repo")).hasText("acme/one")
            assertThat(page.locator("#detail-name")).hasText("pdf-extract")
            val url = page.url()
            assertTrue("url=github.com%2Facme%2Fone" in url && "url=github.com%2Facme%2Ftwo" in url, url)
            assertTrue("skill=acme%2Fone%3Askills%2Fpdf" in url, url)

            // The URL restores repositories and selection.
            val restored = browser.newPage()
            restored.navigate(url)
            restored.waitForSelector("body[data-state=idle]")
            assertThat(restored.locator(".chip")).hasCount(2)
            assertThat(restored.locator("#detail-name")).hasText("pdf-extract")

            // Removing a chip drops its group; with one repository left, the page looks as it did before.
            restored.locator(".chip", Page.LocatorOptions().setHasText("acme/one")).locator(".chip-remove").click()
            restored.waitForSelector("body[data-state=idle]")
            assertThat(restored.locator(".chip")).hasCount(1)
            assertThat(restored.locator(".group-header")).hasCount(0)
            assertThat(restored.locator("#repo-table")).isHidden()
            assertThat(restored.locator("#repo-name")).hasText("acme/two")
            restored.close()
            page.close()
        }
    }

    companion object {
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

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

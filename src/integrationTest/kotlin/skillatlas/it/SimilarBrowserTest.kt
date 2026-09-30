package skillatlas.it

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
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

/** Skill files whose similar skills are known exactly (spec section 5.6), keyed by repository path. */
val SIMILAR_SKILLS: Map<String, String> = listOf(
    Triple("commits", "How to write good commit messages.", "# Commits\n\nWrite the subject in the imperative mood.\n"),
    Triple("docx", "Edit Word documents and extract their text.", "# Word\n\nUnzip the document, then edit the XML.\n"),
    Triple(
        "mps-aspect-typesystem", "Use when defining typesystem rules for MPS languages.",
        "# Typesystem\n\nWrite inference rules and checking rules.\n",
    ),
    Triple(
        "mps-run-configurations", "Create run configurations that run MPS tests.",
        "# Run configurations\n\nPick the test module, then run it.\n",
    ),
    Triple(
        "mps-tests", "Use when writing or modifying tests for MPS languages.",
        "# MPS tests\n\nRun the tests from the test module with Gradle.\n",
    ),
    Triple("pdf-extract", "Extract text and tables from PDF files.", "# PDF\n\nUse pdftotext, then check every table.\n"),
).associate { (name, description, body) -> "skills/$name/SKILL.md" to "---\nname: $name\ndescription: $description\n---\n\n$body" }

/**
 * Drives the *Similar skills* section of the web view in headless Chromium (spec sections
 * 5.4 and 5.6). Every wait is for a page element.
 */
class SimilarBrowserTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var web: WebView
    private lateinit var page: Page

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        sandbox.api.repository("acme/skills", "Acme agent skills")
        sandbox.createRepository("acme/skills", SIMILAR_SKILLS)
        web = sandbox.startWebView()
        page = browser.newPage()
    }

    @AfterTest
    fun tearDown() {
        page.close()
        web.close()
        sandbox.close()
    }

    private fun open(query: String) {
        page.navigate(web.url + query)
        page.waitForSelector("body[data-state=idle]")
    }

    private fun option(name: String) = page.locator("[role=option]", Page.LocatorOptions().setHasText(name))

    private val rows get() = page.locator("#similar .similar-row")

    @Test
    fun `lists similar skills with bars and percentages`() {
        open("?url=github.com/acme/skills&skill=skills/mps-tests")

        assertThat(page.locator("#similar-heading")).hasText("Similar skills")
        assertThat(rows).hasCount(2)
        assertThat(rows.locator(".similar-name")).hasText(arrayOf("mps-run-configurations", "mps-aspect-typesystem"))
        assertThat(rows.locator(".similar-path")).hasText(arrayOf("skills/mps-run-configurations", "skills/mps-aspect-typesystem"))
        assertThat(rows.locator(".similar-score")).hasText(arrayOf("41 %", "22 %"))
        assertThat(rows.nth(0).locator(".similar-fill")).hasAttribute("style", "width: 41%;")
        assertThat(rows.nth(1).locator(".similar-fill")).hasAttribute("style", "width: 22%;")
        assertThat(page.locator("#no-similar")).isHidden()
    }

    @Test
    fun `sits between the GitHub link and the content tabs`() {
        open("?url=github.com/acme/skills&skill=skills/mps-tests")

        val order = page.locator("#detail-github, #similar, .tabs").evaluateAll("nodes => nodes.map(node => node.id || node.className)")
        assertEquals(listOf("detail-github", "similar", "tabs"), order)
    }

    @Test
    fun `clicking a similar skill selects it`() {
        open("?url=github.com/acme/skills&skill=skills/mps-tests")

        rows.filter(Locator.FilterOptions().setHasText("mps-aspect-typesystem")).click()

        assertThat(option("mps-aspect-typesystem")).hasAttribute("aria-selected", "true")
        assertThat(option("mps-tests")).hasAttribute("aria-selected", "false")
        assertThat(page.locator("#detail-name")).hasText("mps-aspect-typesystem")
        assertThat(page.locator("#rendered h1")).hasText("Typesystem")
        assertThat(rows.locator(".similar-name")).hasText(arrayOf("mps-tests", "mps-run-configurations"))
        assertThat(rows.locator(".similar-score")).hasText(arrayOf("22 %", "12 %"))
        assertTrue(page.url().endsWith("&skill=skills%2Fmps-aspect-typesystem"), page.url())
    }

    @Test
    fun `shows a message when there are no similar skills`() {
        open("?url=github.com/acme/skills&skill=skills/commits")

        assertThat(page.locator("#detail-name")).hasText("commits")
        assertThat(page.locator("#no-similar")).hasText("No similar skills found.")
        assertThat(page.locator("#no-similar")).isVisible()
        assertThat(rows).hasCount(0)

        option("docx").click()

        assertThat(rows).hasCount(1)
        assertThat(rows.locator(".similar-name")).hasText("pdf-extract")
        assertThat(rows.locator(".similar-score")).hasText("17 %")
        assertThat(page.locator("#no-similar")).isHidden()
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

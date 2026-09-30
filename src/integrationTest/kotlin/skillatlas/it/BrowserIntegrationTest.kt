package skillatlas.it

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir

/**
 * Drives the real web view in headless Chromium against `serve --port 0`, the stub API and
 * a fixture repository (spec sections 5.4 and 11.3). Every wait is for a page element.
 */
class BrowserIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var web: WebView
    private lateinit var page: Page
    private lateinit var commit: String

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        sandbox.api.repository("acme/skills", "Acme agent skills")
        commit = sandbox.createRepository(
            "acme/skills",
            mapOf(
                ".agents/skills/commits/SKILL.md" to COMMITS,
                ".claude/skills/commits/SKILL.md" to COMMITS,
                "skills/pdf/SKILL.md" to PDF,
                "skills/unsafe/SKILL.md" to UNSAFE,
            ),
        )
        web = sandbox.startWebView()
        page = browser.newPage()
    }

    @AfterTest
    fun tearDown() {
        page.close()
        web.close()
        sandbox.close()
    }

    private fun open(query: String = "?url=github.com/acme/skills") {
        page.navigate(web.url + query)
        page.waitForSelector("body[data-state=idle]")
    }

    private fun option(name: String) = page.locator("[role=option]", Page.LocatorOptions().setHasText(name))

    @Test
    fun `selects the first skill after a scan`() {
        open()

        assertThat(page.locator("[role=option]")).hasCount(3)
        assertThat(option("commits")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#detail-name")).hasText("commits")
        assertThat(page.locator("#detail-paths")).hasText(".agents/skills/commitsalso in .claude/skills/commits")
    }

    @Test
    fun `clicking a skill shows its content on the right`() {
        open()

        option("pdf-extract").click()

        assertThat(option("pdf-extract")).hasAttribute("aria-selected", "true")
        assertThat(option("commits")).hasAttribute("aria-selected", "false")
        assertThat(page.locator("#detail-name")).hasText("pdf-extract")
        assertThat(page.locator("#detail-description")).hasText(PDF_DESCRIPTION)
        assertThat(page.locator("#detail-paths")).hasText("skills/pdf")
        assertThat(page.locator("#detail-github"))
            .hasAttribute("href", "https://github.com/acme/skills/blob/$commit/skills/pdf/SKILL.md")
        assertThat(page.locator("#rendered h1")).hasText("PDF extraction")
        assertThat(page.locator("#rendered table td").first()).hasText("pdftotext")
        assertThat(page.locator("#rendered a", Page.LocatorOptions().setHasText("reference")))
            .hasAttribute("href", "https://github.com/acme/skills/blob/$commit/skills/pdf/reference.md")
        assertTrue(page.url().endsWith("&skill=skills%2Fpdf"), page.url())
    }

    @Test
    fun `shows the full description, which the list shortens`() {
        open()

        option("pdf-extract").click()

        assertThat(option("pdf-extract").locator(".skill-description")).hasText(Pattern.compile(".*…$"))
        assertThat(page.locator("#detail-description")).hasText(PDF_DESCRIPTION)
    }

    @Test
    fun `moves the selection with the arrow keys`() {
        open()

        option("commits").click()
        page.keyboard().press("ArrowDown")
        assertThat(page.locator("#detail-name")).hasText("pdf-extract")
        page.keyboard().press("ArrowDown")
        assertThat(page.locator("#detail-name")).hasText("unsafe-demo")
        page.keyboard().press("ArrowUp")
        assertThat(page.locator("#detail-name")).hasText("pdf-extract")
        assertThat(option("pdf-extract")).isFocused()
    }

    @Test
    fun `the raw tab shows the exact file`() {
        open()
        option("pdf-extract").click()

        page.locator("#tab-raw").click()

        assertThat(page.locator("#raw")).isVisible()
        assertThat(page.locator("#rendered")).isHidden()
        assertEquals(PDF, page.locator("#raw-code").textContent())
    }

    @Test
    fun `reopens the skill from the page URL`() {
        open("?url=github.com/acme/skills&skill=skills/pdf")

        assertThat(option("pdf-extract")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#detail-name")).hasText("pdf-extract")
    }

    @Test
    fun `never runs HTML or scripts from a skill file`() {
        val dialogs = mutableListOf<String>()
        page.onDialog { dialogs += it.message(); it.dismiss() }
        open()

        option("unsafe-demo").click()

        assertThat(page.locator("#rendered")).containsText("<script>alert('x')</script>")
        assertThat(page.locator("#rendered script")).hasCount(0)
        assertThat(page.locator("#rendered img")).hasCount(0)
        assertThat(page.locator("#rendered a", Page.LocatorOptions().setHasText("click me"))).not().hasAttribute(
            "href",
            Pattern.compile("javascript:.*"),
        )
        assertEquals(emptyList(), dialogs)
    }

    @Test
    fun `the divider resizes the panes with the keyboard`() {
        open()
        val before = page.locator("#skills").boundingBox()!!.width

        page.locator("#divider").focus()
        page.keyboard().press("ArrowRight")
        page.keyboard().press("ArrowRight")

        val after = page.locator("#skills").boundingBox()!!.width
        assertEquals(before + 48, after, 1.0)
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

        const val PDF_DESCRIPTION =
            "Extract text and tables from PDF files with pdftotext, then check every table against the original " +
                "page before you rely on it."

        val COMMITS = "---\nname: commits\ndescription: How to write commit messages.\n---\n\n# Commits\n\nUse the imperative mood.\n"

        val PDF = """
            |---
            |name: pdf-extract
            |description: $PDF_DESCRIPTION
            |---
            |
            |# PDF extraction
            |
            || Tool | Use |
            ||------|-----|
            || pdftotext | text |
            |
            |See the [reference](reference.md).
            |""".trimMargin()

        val UNSAFE = """
            |---
            |name: unsafe-demo
            |description: A skill file that tries to run code.
            |---
            |
            |<script>alert('x')</script>
            |
            |<img src=x onerror="alert('y')">
            |
            |[click me](javascript:alert('z'))
            |""".trimMargin()
    }
}

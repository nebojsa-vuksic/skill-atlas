package skillatlas.it

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir

/**
 * Starring skills in the web view, in headless Chromium (spec section 5.11). Every wait is
 * for a page element, and the stars file is the same one the CLI uses.
 */
class StarsBrowserTest {
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

    private val button get() = page.locator("#star-button")

    @Test
    fun `the star button stars the selected skill, and the star stays after a reload`() {
        open("?url=github.com/acme/skills&skill=skills/mps-tests")
        assertThat(button).hasText("☆ Star")
        assertThat(button).hasAttribute("aria-pressed", "false")
        assertThat(option("mps-tests").locator(".star-icon")).hasCount(0)

        button.click()

        assertThat(button).hasText("★ Starred")
        assertThat(button).hasAttribute("aria-pressed", "true")
        val icon = option("mps-tests").locator(".star-icon")
        assertThat(icon).hasText("★")
        assertThat(icon).hasAttribute("title", "starred")
        assertTrue("\"path\": \"skills/mps-tests\"" in sandbox.starsFile.readText(), sandbox.starsFile.readText())

        page.reload()
        page.waitForSelector("body[data-state=idle]")
        assertThat(button).hasText("★ Starred")
        assertThat(option("mps-tests").locator(".star-icon")).hasCount(1)

        button.click()
        assertThat(button).hasText("☆ Star")
        assertThat(option("mps-tests").locator(".star-icon")).hasCount(0)
        assertEquals("No starred skills yet.\n", sandbox.run("stars").stdout)
    }

    @Test
    fun `s toggles the star when focus isn't in a text field`() {
        open("?url=github.com/acme/skills&skill=skills/docx")
        option("docx").focus()

        page.keyboard().press("s")
        assertThat(button).hasText("★ Starred")
        assertThat(option("docx").locator(".star-icon")).hasCount(1)

        // In the filter field, s is just a letter.
        page.locator("#filter").focus()
        page.keyboard().press("s")
        assertThat(page.locator("#filter")).hasValue("s")
        assertThat(button).hasText("★ Starred")

        page.locator("#filter").press("Escape")
        option("docx").focus()
        page.keyboard().press("s")
        assertThat(button).hasText("☆ Star")
    }

    @Test
    fun `the starred-only button lists only starred skills and keeps it in the URL`() {
        sandbox.run("star", "github.com/acme/skills", "mps-tests")
        sandbox.run("star", "github.com/acme/skills", "commits")
        open("?url=github.com/acme/skills&skill=skills/docx")
        // Starred with the CLI, shown in the browser.
        assertThat(page.locator("[role=option] .star-icon")).hasCount(2)

        page.locator("#starred-only").click()

        assertThat(page.locator("#starred-only")).hasAttribute("aria-pressed", "true")
        assertThat(page.locator("#filter")).hasValue("is:starred")
        assertThat(page.locator("[role=option]:visible .skill-name")).hasText(arrayOf("commits", "mps-tests"))
        assertThat(page.locator("#filter-count")).hasText("2 of ${SIMILAR_SKILLS.size}")
        assertThat(page.locator("#filter-count")).hasClass("filter-count active")
        // docx was filtered out, so the first visible skill is selected.
        assertThat(page.locator("#detail-name")).hasText("commits")
        assertTrue("q=is%3Astarred" in page.url(), page.url())

        // Unstarring a skill drops it from the list.
        button.click()
        assertThat(page.locator("[role=option]:visible .skill-name")).hasText(arrayOf("mps-tests"))
        assertThat(page.locator("#detail-name")).hasText("mps-tests")

        page.locator("#starred-only").click()
        assertThat(page.locator("#filter")).hasValue("")
        assertThat(page.locator("#starred-only")).hasAttribute("aria-pressed", "false")
        assertThat(page.locator("[role=option]:visible")).hasCount(SIMILAR_SKILLS.size)
    }

    @Test
    fun `is starred combines with words and survives a reload`() {
        sandbox.run("star", "github.com/acme/skills", "mps-tests")
        sandbox.run("star", "github.com/acme/skills", "commits")
        open("?url=github.com/acme/skills&q=tests%20is:starred")

        assertThat(page.locator("[role=option]:visible .skill-name")).hasText(arrayOf("mps-tests"))
        assertThat(page.locator("#starred-only")).hasAttribute("aria-pressed", "true")
        // is:starred isn't highlighted; the word is.
        assertThat(page.locator("[role=option]:visible mark")).hasText(arrayOf("tests", "tests"))

        page.locator("#starred-only").click()
        assertThat(page.locator("#filter")).hasValue("tests")
    }

    @Test
    fun `a failed change shows the error and keeps the star as it was`() {
        open("?url=github.com/acme/skills&skill=skills/docx")
        sandbox.starsFile.parent.toFile().mkdirs()
        sandbox.starsFile.toFile().writeText("{broken")

        button.click()

        assertThat(page.locator("#error")).hasText("error: could not read stars ${sandbox.starsFile}: not valid stars JSON")
        assertThat(button).hasText("☆ Star")
        assertThat(option("docx").locator(".star-icon")).hasCount(0)
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

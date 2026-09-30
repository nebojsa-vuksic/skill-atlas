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
 * The compact list items and the filter in the left pane of the web view (spec sections 5.4
 * and 5.5), in headless Chromium against `serve --port 0`. Every wait is for a page element.
 */
class FilterBrowserTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var web: WebView
    private lateinit var page: Page

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        sandbox.api.repository("acme/mps", "Language workbench")
        sandbox.createRepository(
            "acme/mps",
            mapOf(
                ".agents/skills/commits/SKILL.md" to COMMITS,
                ".claude/skills/commits/SKILL.md" to COMMITS,
                ".agents/skills/mps-tests/SKILL.md" to MPS_TESTS,
                "plugins/mcp-tools/resources/skills/mps-tests/SKILL.md" to MPS_TESTS,
                "skills/broken/SKILL.md" to BROKEN,
                "skills/long-name/SKILL.md" to LONG_NAME,
                "skills/test-runner/SKILL.md" to TEST_RUNNER,
                "skills/typesystem/SKILL.md" to TYPESYSTEM,
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

    private fun open(query: String = "?url=github.com/acme/mps") {
        page.navigate(web.url + query)
        page.waitForSelector("body[data-state=idle]")
    }

    private fun option(name: String) = page.locator("[role=option]", Page.LocatorOptions().setHasText(name))

    private val filter get() = page.locator("#filter")

    private val visibleNames get() = page.locator("[role=option]:visible .skill-name")

    private fun assertVisible(vararg names: String) = assertThat(visibleNames).hasText(names)

    /** Measures an element: its height, its line height, and whether its text overflows it. */
    private fun measure(selector: String): Map<*, *> = page.locator(selector).evaluate(
        """el => ({
            height: el.getBoundingClientRect().height,
            line: parseFloat(getComputedStyle(el).lineHeight),
            cutX: el.scrollWidth > el.clientWidth,
            cutY: el.scrollHeight > el.clientHeight,
        })""",
    ) as Map<*, *>

    @Test
    fun `list items show only the name and the short description`() {
        open()

        assertVisible("commits", "mps-tests", "broken", LONG, "test-runner", "typesystem")
        assertThat(page.locator("#skills .skill-path")).hasCount(0)
        assertThat(option("commits")).not().containsText(".agents/skills/commits")
        assertThat(option("commits").locator(".skill-description")).hasText("How to write commit messages.")
        assertThat(option("broken").locator(".skill-description")).hasText("(no description)")
        assertThat(page.locator("#detail-paths")).hasText(".agents/skills/commitsalso in .claude/skills/commits")
    }

    @Test
    fun `labels shrink to icons with tooltips`() {
        open()

        val shipped = option("mps-tests").locator(".shipped-icon")
        assertThat(shipped).hasText("◆")
        assertThat(shipped).hasAttribute("title", "shipped in product")
        val copies = option("mps-tests").locator(".copies-icon")
        assertThat(copies).hasText("⧉ 1")
        assertThat(copies).hasAttribute("title", "also in plugins/mcp-tools/resources/skills/mps-tests")
        val warning = option("broken").locator(".warning-icon")
        assertThat(warning).hasText("⚠")
        assertThat(warning).hasAttribute("title", "missing name\nmissing description")
        assertThat(option("broken")).not().containsText("missing name")
        assertThat(option("commits").locator(".shipped-icon, .warning-icon")).hasCount(0)
    }

    @Test
    fun `names take one line and descriptions at most two`() {
        open()
        // The narrowest left pane makes both the long name and the description overflow.
        page.locator("#divider").focus()
        repeat(20) { page.keyboard().press("ArrowLeft") }

        val name = measure("[data-path='skills/long-name'] .skill-name")
        assertEquals((name["line"] as Number).toDouble(), (name["height"] as Number).toDouble(), 0.5, name.toString())
        assertTrue(name["cutX"] as Boolean, name.toString())

        val description = measure("[data-path='skills/long-name'] .skill-description")
        val lines = (description["height"] as Number).toDouble() / (description["line"] as Number).toDouble()
        assertTrue(lines <= 2.05, description.toString())
        assertTrue(description["cutY"] as Boolean, description.toString())
    }

    @Test
    fun `typing shows only the matching skills in their original order`() {
        open()
        assertThat(page.locator("#filter-count")).hasText("6 of 6")
        assertThat(page.locator("#filter-count")).not().hasClass(Pattern.compile(".*\\bactive\\b.*"))

        filter.pressSequentially("test")

        assertVisible("mps-tests", "test-runner", "typesystem")
        assertThat(page.locator("#filter-count")).hasText("3 of 6")
        assertThat(page.locator("#filter-count")).hasClass(Pattern.compile(".*\\bactive\\b.*"))
        assertThat(option("test-runner").locator(".skill-name mark")).hasText("test")
        assertThat(option("test-runner").locator(".skill-description mark")).hasText("test")
        assertThat(option("mps-tests").locator(".skill-name mark")).hasText("test")
        assertTrue(page.url().endsWith("&q=test"), page.url())
    }

    @Test
    fun `every word must match, case-insensitively, in the name or the full description`() {
        open()

        filter.fill("MPS Models")
        assertVisible("mps-tests")
        assertThat(option("mps-tests").locator("mark")).hasText(arrayOf("mps", "MPS", "models"))

        filter.fill("rules node")
        assertVisible("typesystem")
        assertThat(page.locator("#filter-count")).hasText("1 of 6")

        filter.fill("  ")
        assertThat(page.locator("[role=option]:visible")).hasCount(6)
        assertThat(page.locator("#skills mark")).hasCount(0)
    }

    @Test
    fun `a word found only in the full description shows a snippet`() {
        open()
        assertThat(option("typesystem").locator(".skill-description"))
            .hasText("Use when defining type rules, inference rules, checking rules, subtyping rules or quick fixes in a…")

        filter.fill("test")

        val description = option("typesystem").locator(".skill-description")
        assertThat(description).hasText("…or when writing a WhenConcrete test statement that checks them against the…")
        assertThat(description.locator("mark")).hasText("test")
        assertThat(description).hasAttribute("title", TYPESYSTEM_DESCRIPTION)
    }

    @Test
    fun `no match shows the empty message and an empty right pane`() {
        open()

        filter.fill("zzz")

        assertThat(page.locator("#no-match")).hasText("No skills match \"zzz\".")
        assertThat(page.locator("#no-match")).isInViewport()
        assertThat(page.locator("[role=option]:visible")).hasCount(0)
        assertThat(page.locator("#filter-count")).hasText("0 of 6")
        assertThat(page.locator("#detail")).isHidden()
    }

    @Test
    fun `escape clears the filter`() {
        open()
        filter.fill("zzz")
        assertThat(page.locator("#no-match")).isVisible()

        filter.press("Escape")

        assertThat(filter).hasValue("")
        assertThat(filter).isFocused()
        assertThat(page.locator("#no-match")).isHidden()
        assertThat(page.locator("[role=option]:visible")).hasCount(6)
        assertThat(page.locator("#filter-count")).hasText("6 of 6")
        assertThat(option("commits")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#detail-name")).hasText("commits")
        assertTrue(!page.url().contains("q="), page.url())
    }

    @Test
    fun `the page URL reopens the filter`() {
        open("?url=github.com/acme/mps&skill=skills/typesystem&q=test")

        assertThat(filter).hasValue("test")
        assertVisible("mps-tests", "test-runner", "typesystem")
        assertThat(option("typesystem")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#detail-name")).hasText("typesystem")
    }

    @Test
    fun `filtering out the selected skill selects the first visible one`() {
        open()
        option(LONG).click()
        assertThat(page.locator("#detail-name")).hasText(LONG)

        filter.fill("test")

        assertThat(option("mps-tests")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#detail-name")).hasText("mps-tests◆ shipped in product")
        assertTrue(page.url().endsWith("&skill=.agents%2Fskills%2Fmps-tests&q=test"), page.url())
    }

    @Test
    fun `clearing the filter keeps the current selection`() {
        open()
        filter.fill("test")
        option("typesystem").click()

        filter.fill("")

        assertThat(page.locator("[role=option]:visible")).hasCount(6)
        assertThat(option("typesystem")).hasAttribute("aria-selected", "true")
        assertThat(page.locator("#detail-name")).hasText("typesystem")
    }

    @Test
    fun `the keyboard moves between the field and the list`() {
        open()

        page.keyboard().press("/")
        assertThat(filter).isFocused()
        assertThat(filter).hasValue("")

        filter.pressSequentially("test")
        filter.press("ArrowDown")
        assertThat(option("mps-tests")).isFocused()

        page.keyboard().press("ArrowDown")
        assertThat(option("test-runner")).isFocused()
        assertThat(page.locator("#detail-name")).hasText("test-runner")
        page.keyboard().press("ArrowDown")
        page.keyboard().press("ArrowDown")
        assertThat(option("typesystem")).isFocused()
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

        private fun skill(name: String, description: String) =
            "---\nname: $name\ndescription: $description\n---\n\n# $name\n"

        const val LONG = "a-skill-with-a-name-that-is-far-too-long-to-fit-on-one-line-of-the-list"

        const val TYPESYSTEM_DESCRIPTION =
            "Use when defining type rules, inference rules, checking rules, subtyping rules or quick fixes in a " +
                "language, or when writing a WhenConcrete test statement that checks them against the expected " +
                "types of every node."

        val COMMITS = skill("commits", "How to write commit messages.")
        val MPS_TESTS = skill("mps-tests", "Use when writing or modifying tests inside MPS models.")
        val BROKEN = "---\nauthor: nobody\n---\n\n# Broken\n"
        val LONG_NAME = skill(
            LONG,
            "Explains what happens when a name and a description are both longer than the narrow list pane " +
                "can show.",
        )
        val TEST_RUNNER = skill("test-runner", "Runs the unit tests.")
        val TYPESYSTEM = skill("typesystem", TYPESYSTEM_DESCRIPTION)
    }
}

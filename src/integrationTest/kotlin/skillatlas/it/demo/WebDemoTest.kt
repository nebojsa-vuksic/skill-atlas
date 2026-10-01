package skillatlas.it.demo

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Playwright
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import skillatlas.it.Sandbox
import skillatlas.it.WebView

/**
 * The web view demos of spec section 13.3. Each test is one demo: it records a narrated video
 * and compares every key moment with its baseline. Runs on CI only (`./gradlew demoTest`).
 */
@Tag("demo")
class WebDemoTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var web: WebView

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        DemoFixtures.install(sandbox)
        web = sandbox.startWebView()
    }

    @AfterTest
    fun tearDown() {
        web.close()
        sandbox.close()
    }

    private fun Locator.dragBy(demo: WebDemo, dx: Double) {
        val box = boundingBox()!!
        demo.page.mouse().move(box.x + box.width / 2, box.y + 200)
        demo.page.mouse().down()
        demo.page.mouse().move(box.x + box.width / 2 + dx, box.y + 200, com.microsoft.playwright.Mouse.MoveOptions().setSteps(25))
        demo.page.mouse().up()
    }

    @Test
    fun `web-basics`() {
        val lines = listOf(
            "This is Skill Atlas. Hand it a GitHub repository, and it finds every agent skill inside.",
            "One URL, one click.",
            "Eight skills, from the default branch, with the exact commit it read.",
            "Click any skill. You get the full description, where it lives, and the file itself, rendered.",
            "Want the file exactly as written? Hit Raw.",
            "Need more room? Drag the divider. It remembers.",
            "Similar skills are one click away. No AI, just honest math on the words.",
        )
        WebDemo("web-basics", browser, lines).use { demo ->
            val page = demo.page
            page.navigate(web.url)
            demo.idle()
            demo.say(lines[0])
            demo.moment("01-empty")

            demo.type("#url", "github.com/${DemoFixtures.SKILLS}", 45.0)
            demo.say(lines[1])
            page.click("#scan-button")
            demo.idle()
            demo.say(lines[2])
            demo.moment("02-scanned")

            demo.skill("pdf-toolkit").click()
            demo.say(lines[3])
            demo.moment("03-selected")

            page.click("#tab-raw")
            demo.say(lines[4])
            demo.moment("04-raw")
            page.click("#tab-rendered")

            page.locator("#divider").dragBy(demo, 160.0)
            demo.say(lines[5])
            demo.moment("05-divider")

            page.locator(".similar-row").first().click()
            demo.say(lines[6])
            demo.moment("06-similar-opened")
        }
    }

    @Test
    fun `web-search`() {
        val lines = listOf(
            "Now a workbench, where every skill lives in dot agents, dot claude, and sometimes the product too.",
            "Skill Atlas merges identical copies into one entry each.",
            "This one even ships inside the product. The diamond says so.",
            "Type to filter. Every word has to match the name or the description.",
            "When the match hides deep in a description, you get a snippet around it.",
            "No match? It tells you straight.",
            "Escape clears it. And the filter lives in the address, so a reload brings it right back.",
        )
        WebDemo("web-search", browser, lines).use { demo ->
            val page = demo.page
            page.navigate(web.url)
            demo.idle()
            demo.say(lines[0])
            demo.type("#url", "github.com/${DemoFixtures.WORKBENCH}", 45.0)
            page.click("#scan-button")
            demo.idle()
            demo.say(lines[1])
            demo.moment("01-merged")

            demo.skill("generator").click()
            demo.say(lines[2])
            demo.moment("02-shipped")

            demo.type("#filter", "test", 110.0)
            demo.say(lines[3])
            demo.moment("03-filtered")

            demo.skill("typesystem").scrollIntoViewIfNeeded()
            demo.say(lines[4])
            demo.moment("04-snippet")

            page.locator("#filter").fill("")
            demo.type("#filter", "zzz", 120.0)
            demo.say(lines[5])
            demo.moment("05-no-match")

            page.locator("#filter").press("Escape")
            demo.type("#filter", "generator", 90.0)
            page.reload()
            demo.idle()
            demo.say(lines[6])
            demo.moment("06-restored")
        }
    }

    @Test
    fun `web-multi`() {
        val lines = listOf(
            "Why stop at one repository? Paste three at once.",
            "Each gets a chip, a row in the summary, and its own group in the list.",
            "Search runs across every one of them.",
            "Want just one? Say repo, colon, and a name.",
            "Test fixtures aren't skills, so they're set aside.",
            "Similar skills cross repository lines. This one points straight into another collection.",
            "Done with one? Hit the x. Gone.",
        )
        WebDemo("web-multi", browser, lines).use { demo ->
            val page = demo.page
            page.navigate(web.url)
            demo.idle()
            demo.say(lines[0])
            demo.type("#url", listOf(DemoFixtures.SKILLS, DemoFixtures.WORKBENCH, DemoFixtures.FRAMEWORK).joinToString(" ") { "github.com/$it" }, 30.0)
            page.click("#scan-button")
            demo.idle()
            demo.say(lines[1])
            demo.moment("01-three-repositories")

            demo.type("#filter", "test", 110.0)
            demo.say(lines[2])
            demo.moment("02-search-across")

            page.locator("#filter").fill("")
            demo.type("#filter", "repo:framework", 90.0)
            demo.say(lines[3])
            demo.moment("03-repo-qualifier")

            page.locator("#ignored").scrollIntoViewIfNeeded()
            demo.say(lines[4])
            demo.moment("04-ignored")

            demo.skill("split-platform-code").click()
            page.locator("#similar").scrollIntoViewIfNeeded()
            demo.say(lines[5])
            demo.moment("05-cross-similar")

            page.evaluate("() => window.scrollTo(0, 0)")
            page.locator(".chip", com.microsoft.playwright.Page.LocatorOptions().setHasText(DemoFixtures.SKILLS)).locator(".chip-remove").click()
            demo.idle()
            demo.say(lines[6])
            demo.moment("06-chip-removed")
        }
    }

    @Test
    fun `web-stars`() {
        val lines = listOf(
            "Some skills you reach for every day. Let's star them.",
            "Pick a skill and hit the star. It's saved right away.",
            "Or just press S. Starred skills get a gold star in the list.",
            "This star by the filter shows only your favourites.",
            "It's the is starred filter, so it mixes with words. One favourite mentions Word.",
            "Reload, and they're still starred. The terminal sees the very same stars.",
        )
        WebDemo("web-stars", browser, lines).use { demo ->
            val page = demo.page
            page.navigate(web.url)
            demo.idle()
            demo.type("#url", "github.com/${DemoFixtures.SKILLS}", 45.0)
            page.click("#scan-button")
            demo.idle()
            demo.say(lines[0])

            demo.skill("pdf-toolkit").click()
            page.click("#star-button")
            page.waitForSelector("#star-button[aria-pressed=true]")
            demo.say(lines[1])
            demo.moment("01-star-button")

            for (name in listOf("spreadsheet", "docx-editor")) {
                demo.skill(name).click()
                page.keyboard().press("s")
                demo.skill(name).locator(".star-icon").waitFor()
            }
            demo.say(lines[2])
            demo.moment("02-star-key")

            page.click("#starred-only")
            demo.say(lines[3])
            demo.moment("03-starred-only")

            page.locator("#filter").press("End")
            demo.type("#filter", " word", 110.0)
            demo.say(lines[4])
            demo.moment("04-starred-words")

            page.locator("#filter").press("Escape")
            page.reload()
            demo.idle()
            page.click("#starred-only")
            demo.say(lines[5])
            demo.moment("05-after-reload")
        }
    }

    @Test
    fun `web-org`() {
        val lines = listOf(
            "Not just a repository. Paste a whole organization, next to one of its repositories.",
            "Skill Atlas lists the organization, checks each repository for skill files, and clones only those.",
            "One chip for the organization. Agent skills was given twice, so it's scanned once.",
            "Here's what was searched. The fork and the archived repository are skipped.",
            "Search runs across all of them at once.",
            "Remove the organization, and its repositories go with it. The one given on its own stays.",
        )
        WebDemo("web-org", browser, lines).use { demo ->
            val page = demo.page
            page.navigate(web.url)
            demo.idle()
            demo.say(lines[0])
            demo.type("#url", "github.com/${DemoFixtures.SKILLS} github.com/${DemoFixtures.OWNER}", 30.0)
            page.click("#scan-button")
            demo.say(lines[1])
            demo.idle()
            demo.say(lines[2])
            demo.moment("01-owner-chip")

            page.locator("#owner-lines").scrollIntoViewIfNeeded()
            demo.say(lines[3])
            demo.moment("02-searched")

            demo.type("#filter", "pdf", 110.0)
            demo.say(lines[4])
            demo.moment("03-search-across")

            page.locator("#filter").press("Escape")
            page.evaluate("() => window.scrollTo(0, 0)")
            page.locator(".chip.owner .chip-remove").click()
            demo.idle()
            demo.say(lines[5])
            demo.moment("04-owner-removed")
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

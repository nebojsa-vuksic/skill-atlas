package skillatlas.it

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Runs the installed `skill-atlas serve` and talks to it over HTTP (spec sections 5.4 and 11.2). */
class WebViewIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var web: WebView

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        web = sandbox.startWebView()
    }

    @AfterTest
    fun tearDown() {
        web.close()
        sandbox.close()
    }

    @Test
    fun `serves the page and its assets`() {
        val page = web.get("/")
        assertEquals(200, page.status, page.toString())
        assertEquals("text/html; charset=utf-8", page.header("Content-Type"))
        assertTrue("<title>Skill Atlas</title>" in page.body, page.body)
        assertTrue("""<script src="/app.js" defer></script>""" in page.body, page.body)

        val script = web.get("/app.js")
        assertEquals(200, script.status)
        assertEquals("text/javascript; charset=utf-8", script.header("Content-Type"))
        assertTrue("/api/scan?url=" in script.body)

        val style = web.get("/style.css")
        assertEquals(200, style.status)
        assertEquals("text/css; charset=utf-8", style.header("Content-Type"))
    }

    @Test
    fun `scans through the API with the exact JSON and a scan log line`() {
        sandbox.api.repository("acme/skills", "Acme agent skills")
        val commit = sandbox.createRepository(
            "acme/skills",
            mapOf(
                "skills/pdf/SKILL.md" to "---\nname: pdf-extract\ndescription: Extract text and tables from PDF files.\n---\n",
                "skills/csv/SKILL.md" to "---\nname: csv-tools\n---\n",
            ),
        )

        val response = web.get("/api/scan?url=https%3A%2F%2Fgithub.com%2Facme%2Fskills")

        assertEquals(200, response.status, response.toString())
        assertEquals("application/json; charset=utf-8", response.header("Content-Type"))
        assertEquals(
            """{"repository":{"name":"acme/skills","description":"Acme agent skills","branch":"main","commit":"$commit"},""" +
                """"skills":[""" +
                """{"name":"csv-tools","description":"","short_description":"","path":"skills/csv",""" +
                """"also_at":[],"shipped":false,"warnings":["missing description"]},""" +
                """{"name":"pdf-extract","description":"Extract text and tables from PDF files.",""" +
                """"short_description":"Extract text and tables from PDF files.","path":"skills/pdf",""" +
                """"also_at":[],"shipped":false,"warnings":[]}],"ignored":[]}""",
            response.body,
        )
        assertEquals(1, sandbox.scanLogLines().size)
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `reports scan failures as JSON with matching status codes`() {
        val invalid = web.get("/api/scan?url=https%3A%2F%2Fgitlab.com%2Fa%2Fb")
        assertEquals(400, invalid.status)
        assertEquals("""{"error":"not a GitHub repository URL: https://gitlab.com/a/b","exit_code":2}""", invalid.body)

        val missing = web.get("/api/scan?url=github.com/acme/missing")
        assertEquals(404, missing.status)
        assertEquals(
            """{"error":"repository acme/missing not found (is it private? set GITHUB_TOKEN)","exit_code":3}""",
            missing.body,
        )

        val noUrl = web.get("/api/scan")
        assertEquals(400, noUrl.status)
        assertEquals("""{"error":"missing query parameter: url","exit_code":2}""", noUrl.body)

        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `refuses foreign hosts, other methods and unknown paths`() {
        assertEquals(403, web.statusWithHost("evil.example"))
        assertEquals(200, web.statusWithHost(web.url.removePrefix("http://").removeSuffix("/")))
        assertEquals(405, web.post("/api/scan?url=github.com/acme/skills").status)
        assertEquals(404, web.get("/secrets").status)
        assertEquals("default-src 'self'; frame-ancestors 'none'", web.get("/").header("Content-Security-Policy"))
    }
}

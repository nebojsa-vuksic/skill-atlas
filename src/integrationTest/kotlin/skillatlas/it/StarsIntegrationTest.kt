package skillatlas.it

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Starred skills through the installed launcher: `star`, `unstar`, `stars`, the marks in
 * `scan`, and the web API (spec section 5.11). The fixture is [SIMILAR_SKILLS].
 */
class StarsIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox
    private lateinit var commit: String

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        sandbox.api.repository("acme/skills", "Acme agent skills")
        commit = sandbox.createRepository("acme/skills", SIMILAR_SKILLS)
    }

    @AfterTest
    fun tearDown() = sandbox.close()

    private val progress = "Fetching metadata for acme/skills...\nCloning acme/skills (main)...\n"

    private fun stars() = if (sandbox.starsFile.exists()) sandbox.starsFile.readText() else null

    private fun starsFile(vararg stars: Pair<String, String>) = buildString {
        append("{\n    \"stars\": [\n")
        append(
            stars.joinToString(",\n") { (path, name) ->
                "        {\n            \"repository\": \"acme/skills\",\n            \"path\": \"$path\",\n            \"name\": \"$name\"\n        }"
            },
        )
        append("\n    ]\n}\n")
    }

    // ---- CLI ----

    @Test
    fun `star, stars and unstar change the stars file`() {
        val starred = sandbox.run("star", "github.com/acme/skills", "mps-tests")
        assertEquals(0, starred.exitCode, starred.toString())
        assertEquals("Starred mps-tests (acme/skills:skills/mps-tests).\n", starred.stdout)
        assertEquals(progress, starred.stderr)
        assertEquals(starsFile("skills/mps-tests" to "mps-tests"), stars())
        assertEquals(1, sandbox.scanLogLines().size)

        val again = sandbox.run("star", "https://github.com/acme/skills", "MPS-TESTS")
        assertEquals(0, again.exitCode, again.toString())
        assertEquals("mps-tests is already starred (acme/skills:skills/mps-tests).\n", again.stdout)

        val byPath = sandbox.run("star", "github.com/acme/skills", "skills/commits/")
        assertEquals("Starred commits (acme/skills:skills/commits).\n", byPath.stdout)
        assertEquals(starsFile("skills/commits" to "commits", "skills/mps-tests" to "mps-tests"), stars())

        val list = sandbox.run("stars")
        assertEquals(0, list.exitCode, list.toString())
        assertEquals(
            """
            2 starred skills:

              commits    acme/skills:skills/commits
              mps-tests  acme/skills:skills/mps-tests

            """.trimIndent(),
            list.stdout,
        )
        assertEquals("", list.stderr)

        val unstarred = sandbox.run("unstar", "github.com/acme/skills", "mps-tests")
        assertEquals(0, unstarred.exitCode, unstarred.toString())
        assertEquals("Unstarred mps-tests (acme/skills:skills/mps-tests).\n", unstarred.stdout)
        assertEquals("mps-tests isn't starred (acme/skills:skills/mps-tests).\n", sandbox.run("unstar", "github.com/acme/skills", "mps-tests").stdout)
        assertEquals(starsFile("skills/commits" to "commits"), stars())

        // Every star and unstar scanned once; stars didn't scan.
        assertEquals(5, sandbox.scanLogLines().size)
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `stars says when there are none, without scanning`() {
        val run = sandbox.run("stars")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals("No starred skills yet.\n", run.stdout)
        assertEquals(emptyList(), sandbox.scanLogLines())
        assertFalse(sandbox.starsFile.exists())
    }

    @Test
    fun `scan tags starred skills and is starred lists only them`() {
        sandbox.run("star", "github.com/acme/skills", "mps-tests")
        sandbox.run("star", "github.com/acme/skills", "docx")

        val list = sandbox.run("scan", "github.com/acme/skills")
        assertEquals(0, list.exitCode, list.toString())
        assertTrue("\n  docx  [starred]\n    Edit Word documents and extract their text.\n" in list.stdout, list.stdout)
        assertTrue("\n  mps-tests  [starred]\n" in list.stdout, list.stdout)
        assertTrue("\n  commits\n" in list.stdout, list.stdout)

        val filtered = sandbox.run("scan", "github.com/acme/skills", "--filter", "is:starred tests")
        assertEquals(
            """
            Repository:  acme/skills
            Description: Acme agent skills
            Commit:      $commit (main)

            Found 6 skills, 1 match "is:starred tests":

              mps-tests  [starred]
                Use when writing or modifying tests for MPS languages.
                skills/mps-tests

            """.trimIndent(),
            filtered.stdout,
        )

        val detail = sandbox.run("scan", "github.com/acme/skills", "--skill", "mps-tests")
        assertTrue("\nSkill:       mps-tests  [starred]\nPath:        skills/mps-tests\n" in detail.stdout, detail.stdout)
    }

    @Test
    fun `stars are shown across several repositories`() {
        sandbox.api.repository("acme/other", null)
        sandbox.createRepository("acme/other", mapOf("skills/docx/SKILL.md" to "---\nname: docx\ndescription: Other Word skill.\n---\n"))
        sandbox.run("star", "github.com/acme/other", "docx")

        val run = sandbox.run("scan", "github.com/acme/skills", "github.com/acme/other", "--filter", "is:starred")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue("Found 6 skills, none match \"is:starred\".\n" in run.stdout, run.stdout)
        assertTrue("Found 1 skill, 1 match \"is:starred\":\n\n  docx  [starred]\n    Other Word skill.\n    skills/docx\n" in run.stdout, run.stdout)
        assertTrue(run.stdout.endsWith("Scanned 2 repositories: 1 of 7 skills match \"is:starred\"\n"), run.stdout)
    }

    @Test
    fun `star finds the skill like --skill and fails the same way`() {
        val missing = sandbox.run("star", "github.com/acme/skills", "nope")
        assertEquals(6, missing.exitCode, missing.toString())
        assertEquals("", missing.stdout)
        assertEquals(progress + "error: no skill 'nope' in acme/skills\n", missing.stderr)
        // The scan itself succeeded, so it is logged.
        assertEquals(1, sandbox.scanLogLines().size)

        sandbox.api.repository("acme/dupes", null)
        sandbox.createRepository(
            "acme/dupes",
            mapOf(
                "a/review/SKILL.md" to "---\nname: review\ndescription: Review code.\n---\n",
                "b/review/SKILL.md" to "---\nname: review\ndescription: Review documents.\n---\n",
            ),
        )
        val ambiguous = sandbox.run("unstar", "github.com/acme/dupes", "review")
        assertEquals(2, ambiguous.exitCode, ambiguous.toString())
        assertTrue(
            ambiguous.stderr.endsWith("error: skill name 'review' matches 2 skills: a/review, b/review; pass a path instead\n"),
            ambiguous.stderr,
        )

        val unknown = sandbox.run("star", "github.com/acme/missing", "pdf")
        assertEquals(3, unknown.exitCode, unknown.toString())
        assertEquals(2, sandbox.scanLogLines().size)

        assertEquals(2, sandbox.run("star", "github.com/acme/skills").exitCode)
        assertFalse(sandbox.starsFile.exists())
    }

    @Test
    fun `an invalid stars file warns in scan, fails a change, and is left untouched`() {
        sandbox.starsFile.parent.createDirectories()
        sandbox.starsFile.writeText("{broken")
        val message = "could not read stars ${sandbox.starsFile}: not valid stars JSON"

        val scan = sandbox.run("scan", "github.com/acme/skills")
        assertEquals(0, scan.exitCode, scan.toString())
        assertEquals("warning: $message\n$progress", scan.stderr)
        assertFalse("[starred]" in scan.stdout)

        val star = sandbox.run("star", "github.com/acme/skills", "docx")
        assertEquals(1, star.exitCode, star.toString())
        assertEquals(progress + "error: $message\n", star.stderr)

        val list = sandbox.run("stars")
        assertEquals(1, list.exitCode, list.toString())
        assertEquals("error: $message\n", list.stderr)
        assertEquals("{broken", sandbox.starsFile.readText())
        assertEquals(2, sandbox.scanLogLines().size)
    }

    @Test
    fun `star uses the rich view in a terminal`() {
        val run = sandbox.runInTerminal("star", "github.com/acme/skills", "pdf-extract")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue("\u001B[" in run.stdout)
        assertTrue(" ★ Starred pdf-extract (acme/skills:skills/pdf-extract)." in stripEscapeCodes(run.stdout), run.stdout)

        val list = sandbox.runInTerminal("stars")
        assertTrue(" ★ pdf-extract  acme/skills:skills/pdf-extract" in stripEscapeCodes(list.stdout), list.stdout)
        val scan = sandbox.runInTerminal("scan", "github.com/acme/skills")
        assertTrue(" ● pdf-extract  ★ starred" in stripEscapeCodes(scan.stdout), scan.stdout)
    }

    @Test
    fun `browse stars the selected skill with s`() {
        val run = sandbox.runInTerminal(
            "browse", "github.com/acme/skills",
            columns = 110, rows = 30,
            steps = listOf("wait:6 of 6", "send:s", "wait:★ starred", "send:q"),
        )

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(starsFile("skills/commits" to "commits"), stars())
    }

    // ---- Web API ----

    private val json = "Content-Type" to "application/json"

    private fun starBody(path: String, name: String, starred: Boolean) =
        """{"repository":"acme/skills","path":"$path","also_at":[],"name":"$name","starred":$starred}"""

    @Test
    fun `the web API reports stars and changes them`() {
        sandbox.startWebView().use { web ->
            val scan = web.get("/api/scans?url=github.com/acme/skills")
            assertTrue(""""path":"skills/mps-tests","also_at":[],"shipped":false,"starred":false,""" in scan.body, scan.body)

            val starred = web.post("/api/star", starBody("skills/mps-tests", "mps-tests", true), json)
            assertEquals(200, starred.status, starred.toString())
            assertEquals("""{"starred":true}""", starred.body)
            assertEquals("application/json; charset=utf-8", starred.header("Content-Type"))
            assertEquals(starsFile("skills/mps-tests" to "mps-tests"), stars())

            // The cached scan, and the single-repository API, both show the new star.
            assertTrue(""""path":"skills/mps-tests","also_at":[],"shipped":false,"starred":true,""" in web.get("/api/scans?url=github.com/acme/skills").body)
            assertTrue(""""path":"skills/mps-tests","also_at":[],"shipped":false,"starred":true,""" in web.get("/api/scan?url=github.com/acme/skills").body)
            assertEquals(2, sandbox.scanLogLines().size)

            // Starring again is fine, and extra skill fields from /api/scans are ignored.
            val again = web.post("/api/star", starBody("skills/mps-tests", "mps-tests", true).dropLast(1) + ""","content":"x"}""", json)
            assertEquals("""{"starred":true}""", again.body)

            val unstarred = web.post("/api/star", starBody("skills/mps-tests", "mps-tests", false), json)
            assertEquals("""{"starred":false}""", unstarred.body)
            assertEquals("No starred skills yet.\n", sandbox.run("stars").stdout)
        }
    }

    @Test
    fun `the star API turns away other sites and bad requests`() {
        sandbox.startWebView().use { web ->
            val body = starBody("skills/docx", "docx", true)
            assertEquals(415, web.post("/api/star", body).status)
            assertEquals(415, web.post("/api/star", body, "Content-Type" to "text/plain").status)
            assertEquals(403, web.post("/api/star", body, json, "Origin" to "https://evil.example").status)
            assertEquals(403, web.post("/api/star", body, json, "Origin" to "null").status)
            val get = web.get("/api/star")
            assertEquals(405, get.status)
            assertEquals("POST", get.header("Allow"))

            for ((request, reason) in listOf(
                "{broken" to "expected JSON with repository, path, name and starred",
                """{"repository":"acme/skills","path":"skills/docx","name":"docx"}""" to "expected JSON with repository, path, name and starred",
                """{"repository":"","path":"skills/docx","name":"docx","starred":true}""" to "repository and path can't be empty",
                "x".repeat(64 * 1024 + 1) to "the body is larger than 64 KB",
            )) {
                val response = web.post("/api/star", request, json)
                assertEquals(400, response.status, response.toString())
                assertEquals("""{"error":"invalid star request: $reason","exit_code":2}""", response.body)
            }
            assertFalse(sandbox.starsFile.exists())

            val sameOrigin = web.post("/api/star", body, "Content-Type" to "application/json; charset=utf-8", "Origin" to web.url.removeSuffix("/"))
            assertEquals(200, sameOrigin.status, sameOrigin.toString())
            assertEquals(starsFile("skills/docx" to "docx"), stars())
        }
    }

    @Test
    fun `the star API reports a stars file it can't read`() {
        sandbox.starsFile.parent.createDirectories()
        sandbox.starsFile.writeText("{broken")
        sandbox.startWebView().use { web ->
            val response = web.post("/api/star", starBody("skills/docx", "docx", true), json)

            assertEquals(500, response.status, response.toString())
            assertEquals("""{"error":"could not read stars ${sandbox.starsFile}: not valid stars JSON","exit_code":1}""", response.body)
            assertEquals("{broken", Files.readString(sandbox.starsFile))
            // Scans still work, with no stars.
            assertEquals(200, web.get("/api/scans?url=github.com/acme/skills").status)
        }
    }

    private companion object {
        val ESCAPE_CODES = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(\u0007|\u001B\\)|\u001B[=>78]""")

        fun stripEscapeCodes(text: String) = text.replace(ESCAPE_CODES, "").replace("\r", "")
    }
}

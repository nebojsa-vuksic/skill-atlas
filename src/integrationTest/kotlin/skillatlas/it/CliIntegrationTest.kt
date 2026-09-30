package skillatlas.it

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Runs the installed `skill-atlas` launcher as a separate process and checks its real
 * output, exit codes, scan log, and cleanup (spec section 11.2).
 */
class CliIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
    }

    @AfterTest
    fun tearDown() = sandbox.close()

    private fun createSkillsRepository(): String {
        sandbox.api.repository("acme/skills", "Acme agent skills")
        return sandbox.createRepository(
            "acme/skills",
            mapOf(
                "skills/pdf/SKILL.md" to "---\nname: pdf-extract\ndescription: Extract text and tables from PDF files.\n---\n\n# PDF\n",
                "skills/long/SKILL.md" to "---\nname: academy-guide\ndescription: >\n  $LONG_DESCRIPTION\n---\n",
                "skills/broken/SKILL.md" to "---\nname: [oops\n---\n",
                "skills/csv/SKILL.md" to "---\nname: csv-tools\n---\n",
                "node_modules/dep/SKILL.md" to "---\nname: ignored\ndescription: Must not be listed.\n---\n",
                "README.md" to "# Acme skills\n",
            ),
        )
    }

    @Test
    fun `scans a repository and prints the exact plain report`() {
        val commit = createSkillsRepository()

        val run = sandbox.run("scan", "https://github.com/acme/skills")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            """
            Repository:  acme/skills
            Description: Acme agent skills
            Commit:      $commit (main)

            Found 4 skills:

              broken  [warning: invalid frontmatter]
                (no description)
                skills/broken

              csv-tools  [warning: missing description]
                (no description)
                skills/csv

              academy-guide
                Stop and check this skill before finishing any reply to a question about how to use Claude or a…
                skills/long

              pdf-extract
                Extract text and tables from PDF files.
                skills/pdf

            """.trimIndent(),
            run.stdout,
        )
        assertEquals("Fetching metadata for acme/skills...\nCloning acme/skills (main)...\n", run.stderr)
        assertTrue('\u001B' !in run.stdout + run.stderr, "piped output must not contain escape codes")

        val log = sandbox.scanLogLines()
        assertEquals(1, log.size, log.toString())
        assertTrue(
            Regex(
                """\{"scanned_at":"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ","repository":"acme/skills",""" +
                    """"description":"Acme agent skills","branch":"main","commit":"$commit","skill_count":4}""",
            ).matches(log.single()),
            log.single(),
        )
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `prints the same report for every accepted URL form`() {
        createSkillsRepository()
        val forms = listOf(
            "https://github.com/acme/skills",
            "https://github.com/acme/skills/",
            "https://github.com/acme/skills.git",
            "http://github.com/acme/skills",
            "github.com/acme/skills",
            "git@github.com:acme/skills.git",
        )

        val runs = forms.associateWith { sandbox.run("scan", it) }

        val reference = runs.getValue(forms.first())
        assertEquals(0, reference.exitCode, reference.toString())
        for ((form, run) in runs) {
            assertEquals(0, run.exitCode, "$form\n$run")
            assertEquals(reference.stdout, run.stdout, form)
        }
        assertEquals(forms.size, sandbox.scanLogLines().size)
    }

    @Test
    fun `reports a repository without skills or description`() {
        sandbox.api.repository("acme/docs", description = null)
        val commit = sandbox.createRepository("acme/docs", mapOf("README.md" to "Nothing to see.\n"))

        val run = sandbox.run("scan", "github.com/acme/docs")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            """
            Repository:  acme/docs
            Description: (none)
            Commit:      $commit (main)

            No skills found.

            """.trimIndent(),
            run.stdout,
        )
    }

    @Test
    fun `rejects an invalid URL with exit code 2`() {
        val run = sandbox.run("scan", "https://gitlab.com/a/b")

        assertEquals(2, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertEquals("error: not a GitHub repository URL: https://gitlab.com/a/b\n", run.stderr)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `reports an unknown repository with exit code 3`() {
        val run = sandbox.run("scan", "https://github.com/acme/missing")

        assertEquals(3, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertEquals(
            "Fetching metadata for acme/missing...\n" +
                "error: repository acme/missing not found (is it private? set GITHUB_TOKEN)\n",
            run.stderr,
        )
    }

    @Test
    fun `reports an empty repository with exit code 4`() {
        sandbox.api.repository("acme/empty", "Nothing yet")
        sandbox.createEmptyRepository("acme/empty")

        val run = sandbox.run("scan", "https://github.com/acme/empty")

        assertEquals(4, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertEquals(
            "Fetching metadata for acme/empty...\n" +
                "Cloning acme/empty (main)...\n" +
                "error: branch 'main' not found in acme/empty (is the repository empty?)\n",
            run.stderr,
        )
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `reports rate limiting with exit code 5`() {
        sandbox.api.rateLimited("acme/skills")

        val run = sandbox.run("scan", "https://github.com/acme/skills")

        assertEquals(5, run.exitCode, run.toString())
        assertEquals(
            "Fetching metadata for acme/skills...\n" +
                "error: GitHub API rate limit exceeded; set GITHUB_TOKEN to raise the limit\n",
            run.stderr,
        )
    }

    @Test
    fun `prints help and version`() {
        val help = sandbox.run("--help")
        assertEquals(0, help.exitCode, help.toString())
        assertTrue(help.stdout.startsWith("Usage: skill-atlas [<options>] <command> [<args>]..."), help.stdout)
        assertTrue(help.stdout.contains("scan"), help.stdout)
        assertEquals("", help.stderr)

        val version = sandbox.run("--version")
        assertEquals(0, version.exitCode, version.toString())
        assertEquals("skill-atlas $VERSION\n", version.stdout)
    }

    @Test
    fun `treats missing or unknown arguments as usage errors`() {
        for (args in listOf(emptyList(), listOf("scan"), listOf("scan", "--nope", "x"), listOf("frobnicate"))) {
            val run = sandbox.run(*args.toTypedArray())
            assertEquals(2, run.exitCode, "args=$args\n$run")
            assertEquals("", run.stdout, "args=$args")
            assertTrue(run.stderr.contains("Usage: skill-atlas"), "args=$args\n$run")
        }
    }

    @Test
    fun `shows the highlighted report in a terminal`() {
        val commit = createSkillsRepository()

        val run = sandbox.runInTerminal("scan", "https://github.com/acme/skills")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue("\u001B[" in run.stdout, "the rich view should use escape codes")
        val screen = stripEscapeCodes(run.stdout)
        assertTrue("Cloning acme/skills (main)…" in screen, screen)
        assertEquals(
            listOf(
                "  SKILL ATLAS",
                "",
                " Repository   acme/skills",
                " Description  Acme agent skills",
                " Commit       $commit  main",
                "",
                " 4 skills found",
                "",
                " ● broken  ⚠ invalid frontmatter",
                "   (no description)",
                "   skills/broken",
                "",
                " ● csv-tools  ⚠ missing description",
                "   (no description)",
                "   skills/csv",
                "",
                " ● academy-guide",
                "   Stop and check this skill before finishing any reply to a question about how to use Claude or a…",
                "   skills/long",
                "",
                " ● pdf-extract",
                "   Extract text and tables from PDF files.",
                "   skills/pdf",
            ),
            reportLines(screen),
        )
        assertEquals(1, sandbox.scanLogLines().size)
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `stops with exit code 130 when interrupted in a terminal`() {
        sandbox.api.hang("acme/skills")

        val run = sandbox.runInTerminal("scan", "https://github.com/acme/skills", interruptAfter = "Fetching metadata")

        assertEquals(130, run.exitCode, run.toString())
        assertTrue("error: scan interrupted" in stripEscapeCodes(run.stdout), run.toString())
        assertEquals(emptyList(), sandbox.scanLogLines())
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    private companion object {
        const val LONG_DESCRIPTION =
            "Stop and check this skill before finishing any reply to a question about how to use Claude or a Claude product."

        private val ESCAPE_CODES = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(\u0007|\u001B\\)|\u001B[=>78]""")

        fun stripEscapeCodes(text: String) = text.replace(ESCAPE_CODES, "").replace("\r", "")

        /** The static report printed at the end, without the status line frames before it. */
        fun reportLines(screen: String): List<String> {
            val lines = screen.lines().map { it.trimEnd() }
            val start = lines.indexOfFirst { it.trim() == "SKILL ATLAS" }
            check(start >= 0) { "no report in:\n$screen" }
            return lines.drop(start).dropLastWhile { it.isEmpty() }
        }
    }
}

package skillatlas.it

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `scan --filter`, `scan --skill` and `browse`, run through the installed launcher
 * (spec sections 5.7 and 5.8). The fixture is [SIMILAR_SKILLS], whose similar skills are known.
 */
class CliFilterAndBrowseIntegrationTest {
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

    private val header get() = "Repository:  acme/skills\nDescription: Acme agent skills\nCommit:      $commit (main)\n\n"

    @Test
    fun `scan --filter lists only matching skills`() {
        val run = sandbox.run("scan", "github.com/acme/skills", "--filter", "TESTS")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            header + """
                Found 6 skills, 2 match "TESTS":

                  mps-run-configurations
                    Create run configurations that run MPS tests.
                    skills/mps-run-configurations

                  mps-tests
                    Use when writing or modifying tests for MPS languages.
                    skills/mps-tests

            """.trimIndent(),
            run.stdout,
        )
        assertEquals(1, sandbox.scanLogLines().size)
    }

    @Test
    fun `scan --filter says when nothing matches`() {
        val run = sandbox.run("scan", "github.com/acme/skills", "-f", "zzz")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(header + "Found 6 skills, none match \"zzz\".\n", run.stdout)
    }

    @Test
    fun `scan --skill prints the skill, its similar skills and its file`() {
        val run = sandbox.run("scan", "github.com/acme/skills", "--skill", "mps-tests")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            header + """
                Skill:       mps-tests
                Path:        skills/mps-tests
                GitHub:      https://github.com/acme/skills/blob/$commit/skills/mps-tests/SKILL.md

                Description:
                  Use when writing or modifying tests for MPS languages.

                Similar skills:
                  mps-run-configurations  ████░░░░░░  41 %  skills/mps-run-configurations
                  mps-aspect-typesystem   ██░░░░░░░░  22 %  skills/mps-aspect-typesystem

                SKILL.md:
                  ---
                  name: mps-tests
                  description: Use when writing or modifying tests for MPS languages.
                  ---

                  # MPS tests

                  Run the tests from the test module with Gradle.

            """.trimIndent(),
            run.stdout,
        )
        assertEquals(1, sandbox.scanLogLines().size)
    }

    @Test
    fun `scan --skill finds a skill by path`() {
        val run = sandbox.run("scan", "github.com/acme/skills", "-s", "skills/commits/")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(run.stdout.contains("Skill:       commits\n"), run.stdout)
        assertTrue(run.stdout.contains("Similar skills:\n  No similar skills found.\n"), run.stdout)
    }

    @Test
    fun `scan --skill reports an unknown skill with exit code 6`() {
        val run = sandbox.run("scan", "github.com/acme/skills", "--skill", "nope")

        assertEquals(6, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertTrue(run.stderr.endsWith("error: no skill 'nope' in acme/skills\n"), run.stderr)
    }

    @Test
    fun `scan --skill reports an ambiguous name with exit code 2`() {
        sandbox.api.repository("acme/names", null)
        sandbox.createRepository(
            "acme/names",
            mapOf(
                ".agents/skills/review/SKILL.md" to "---\nname: review\ndescription: Review pull requests.\n---\n",
                ".claude/skills/review/SKILL.md" to "---\nname: review\ndescription: Review documents.\n---\n",
            ),
        )

        val run = sandbox.run("scan", "github.com/acme/names", "--skill", "Review")

        assertEquals(2, run.exitCode, run.toString())
        assertTrue(
            run.stderr.endsWith(
                "error: skill name 'Review' matches 2 skills: .agents/skills/review, .claude/skills/review; pass a path instead\n",
            ),
            run.stderr,
        )
    }

    @Test
    fun `--filter and --skill can't be used together`() {
        val run = sandbox.run("scan", "github.com/acme/skills", "--filter", "a", "--skill", "b")

        assertEquals(2, run.exitCode, run.toString())
        assertEquals("error: --filter and --skill can't be used together\n", run.stderr)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `scan --filter and --skill use the rich view in a terminal`() {
        val filtered = sandbox.runInTerminal("scan", "github.com/acme/skills", "--filter", "tests")
        assertEquals(0, filtered.exitCode, filtered.toString())
        assertTrue("\u001B[" in filtered.stdout)
        assertTrue(" 2 of 6 skills match \"tests\"" in stripEscapeCodes(filtered.stdout), filtered.stdout)

        val detail = sandbox.runInTerminal("scan", "github.com/acme/skills", "--skill", "mps-tests")
        assertEquals(0, detail.exitCode, detail.toString())
        val screen = stripEscapeCodes(detail.stdout)
        assertTrue(" Skill        mps-tests" in screen, screen)
        assertTrue("   mps-run-configurations  ████░░░░░░  41 %  skills/mps-run-configurations" in screen, screen)
    }

    @Test
    fun `browse needs a terminal`() {
        val run = sandbox.run("browse", "github.com/acme/skills")

        assertEquals(2, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertEquals("error: browse needs an interactive terminal; use \"skill-atlas scan\" instead\n", run.stderr)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `browse filters, jumps to a similar skill and quits`() {
        val run = sandbox.runInTerminal(
            "browse", "github.com/acme/skills",
            columns = 110, rows = 30,
            steps = listOf(
                "wait:6 of 6", "send:/", "send:tests",
                "wait:2 of 6", "send:\\r",
                "send:\\t", "wait:▸ mps-tests",
                "send:\\r", "wait:tab similar",
                "send:q",
            ),
        )

        assertEquals(0, run.exitCode, run.toString())
        val screen = stripEscapeCodes(run.stdout)
        // The filter narrowed the list and selected the first match; Tab then Enter opened its most similar skill.
        val filtered = screen.indexOf("/ tests▏")
        val similar = screen.indexOf("▸ mps-tests", filtered)
        assertTrue(filtered >= 0 && similar > filtered, screen)
        assertTrue(" SKILL ATLAS   acme/skills  ${commit.take(12)} main" in screen, screen)
        assertEquals(1, sandbox.scanLogLines().size)
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `browse reports scan errors with their exit codes`() {
        val run = sandbox.runInTerminal("browse", "github.com/acme/missing")

        assertEquals(3, run.exitCode, run.toString())
        assertTrue("error: repository acme/missing not found (is it private? set GITHUB_TOKEN)" in stripEscapeCodes(run.stdout), run.stdout)
    }

    private companion object {
        val ESCAPE_CODES = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(\u0007|\u001B\\)|\u001B[=>78]""")

        fun stripEscapeCodes(text: String) = text.replace(ESCAPE_CODES, "").replace("\r", "")
    }
}

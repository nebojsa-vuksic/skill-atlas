package skillatlas.it

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The interactive shell, driven through the installed launcher in a pseudo-terminal
 * (spec section 5.9). Every key is typed only after the text it depends on is on screen.
 */
class ShellIntegrationTest {
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

    private val header
        get() = lines(
            "  SKILL ATLAS",
            "",
            " Repository   acme/skills",
            " Description  Acme agent skills",
            " Commit       $commit  main",
        )

    @Test
    fun `scan, filter and a completed skill name, then quit`() {
        val run = sandbox.runInTerminal(
            "shell",
            steps = listOf(
                "wait:type / for commands", "send:/sc",
                "wait:▸ /scan <url>", "send:\\t",
                "wait:❯ /scan ", "send:github.com/acme/skills\\r",
                "wait:6 skills found", "send:/filter tests\\r",
                "wait:2 of 6 skills match", "send:/skill mps-te",
                "wait:▸ mps-tests", "send:\\t",
                "wait:❯ /skill mps-tests", "send:\\r",
                "wait:SKILL.md", "send:/quit\\r",
            ),
        )

        assertEquals(0, run.exitCode, run.toString())
        val screen = screen(run.stdout)
        val scan = screen.indexOf(
            "\n$header\n\n 6 skills found\n\n ● commits\n   How to write good commit messages.\n   skills/commits\n",
        )
        // The echo is printed first, then the spinner runs until the report replaces it.
        val echo = Regex("❯ /scan github\\.com/acme/skills\n[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏] ").find(screen)?.range?.first ?: -1
        assertTrue(echo >= 0 && scan > echo, screen)
        val filter = screen.indexOf(
            lines(
                "❯ /filter tests",
                header,
                "",
                " 2 of 6 skills match \"tests\"",
                "",
                " ● mps-run-configurations",
                "   Create run configurations that run MPS tests.",
                "   skills/mps-run-configurations",
                "",
                " ● mps-tests",
                "   Use when writing or modifying tests for MPS languages.",
                "   skills/mps-tests",
                "",
                "",
            ),
            scan,
        )
        assertTrue(filter > scan, screen)
        val skill = screen.indexOf("❯ /skill mps-tests\n$header\n\n Skill        mps-tests\n Path         skills/mps-tests\n", filter)
        assertTrue(skill > filter, screen)
        assertTrue("   mps-run-configurations  ████░░░░░░  41 %  skills/mps-run-configurations\n" in screen.substring(skill), screen)
        assertTrue(screen.indexOf("❯ /quit", skill) > skill, screen)

        assertEquals(1, sandbox.scanLogLines().size)
        assertTrue(sandbox.scanLogLines().single().contains(""""repository":"acme/skills""""))
        assertEquals(emptyList(), sandbox.leftoverCloneDirectories())
    }

    @Test
    fun `no arguments in a terminal open the shell, and ctrl-d leaves it`() {
        val run = sandbox.runInTerminal(steps = listOf("wait:type / for commands", "send:/repo\\r", "wait:No repository yet", "send:\\x04"))

        assertEquals(0, run.exitCode, run.toString())
        assertTrue("❯ /repo\n No repository yet — run /scan <url> first.\n" in screen(run.stdout), run.stdout)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `errors are shown inline and the shell keeps running`() {
        val run = sandbox.runInTerminal(
            "shell",
            steps = listOf(
                "wait:type / for commands", "send:/scan github.com/acme/missing\\r",
                "wait:error: repository acme/missing not found", "send:/nope\\r",
                "wait:unknown command", "send:/quit\\r",
            ),
        )

        assertEquals(0, run.exitCode, run.toString())
        val screen = screen(run.stdout)
        assertTrue(" error: repository acme/missing not found (is it private? set GITHUB_TOKEN)\n" in screen, screen)
        assertTrue(" error: unknown command /nope; type /help for the list\n" in screen, screen)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `ctrl-c cancels only the running scan`() {
        sandbox.api.hang("acme/slow")

        val run = sandbox.runInTerminal(
            "shell",
            steps = listOf(
                "wait:type / for commands", "send:/scan github.com/acme/slow\\r",
                "wait:Fetching metadata for acme/slow", "send:\\x03",
                "wait:error: scan interrupted", "send:/quit\\r",
            ),
        )

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(" error: scan interrupted\n" in screen(run.stdout), run.stdout)
        assertEquals(emptyList(), sandbox.scanLogLines())
    }

    @Test
    fun `shell needs a terminal`() {
        val run = sandbox.run("shell")

        assertEquals(2, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertEquals("error: shell needs an interactive terminal; use \"skill-atlas scan\" instead\n", run.stderr)
    }

    @Test
    fun `no arguments without a terminal still print the usage`() {
        val run = sandbox.run()

        assertEquals(2, run.exitCode, run.toString())
        assertEquals("", run.stdout)
        assertTrue(run.stderr.startsWith("Usage: skill-atlas [<options>] <command> [<args>]..."), run.stderr)
    }

    private companion object {
        val ESCAPE_CODES = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(\u0007|\u001B\\)|\u001B[=>78]""")

        /** What a user sees in the scrollback: no escape codes, and no trailing spaces on a line. */
        fun lines(vararg lines: String) = lines.joinToString("\n")

        fun screen(text: String) = text.replace(ESCAPE_CODES, "").replace("\r", "").lines().joinToString("\n") { it.trimEnd() }
    }
}

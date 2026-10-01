package skillatlas

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/** The shell's commands and how their output is printed (spec section 5.9). */
class ShellSessionTest {
    @TempDir
    lateinit var dir: Path

    private val sha = "49d37b63488a0a8e42eb0130cb867fd508f398ac"
    private val skills = listOf(
        Skill("mps-tests", "Use when writing or modifying tests for MPS languages.", "skills/mps-tests"),
        Skill("mps-run", "Create run configurations that run MPS tests.", "skills/mps-run"),
        Skill("commits", "Write good commit messages.", "skills/commits"),
    )
    private val result = ScanResult(RepositoryMetadata("acme/skills", "Acme agent skills", "main"), "main", sha, skills)
    private val similar = SkillSimilarity.compute(skills, emptyMap())
    private val clock = Clock.fixed(Instant.parse("2026-09-30T10:28:00Z"), ZoneOffset.UTC)
    private val started = mutableListOf<WebServer.Running>()

    private val scanLog by lazy { ScanLog(dir.resolve("state/scans.log")) }
    private val stars by lazy { StarStore(dir.resolve("data/stars.json")) }

    // Nothing here scans: /scan only asks the view to scan in the background.
    private val scanner = Scanner(GitHubClient(apiBaseUrl = "http://127.0.0.1:9"), Git())

    private fun session(startWebView: (Int, (String) -> Unit) -> WebServer.Running = { port, warn ->
        WebServer(scanner, scanLog, stars, clock, warn).start(port).also { started += it }
    }) = ShellSession(scanner, scanLog, stars, clock, startWebView)

    private fun scanned(session: ShellSession = session()) = session.also { it.adopt(CurrentRepository(result, similar)) }

    private fun blocks(outcome: ShellOutcome) = assertIs<ShellOutcome.Print>(outcome).blocks

    @AfterTest
    fun tearDown() = started.forEach { it.close() }

    @Test
    fun `commands that need a repository say so until there is one`() {
        val session = session()
        for (input in listOf("/filter x", "/skill a", "/similar a", "/star a", "/unstar a", "/browse", "/repo", "pdf")) {
            assertEquals(listOf(ShellSession.NO_REPOSITORY), blocks(session.execute(input)), input)
        }
        assertEquals("No repository yet — run /scan <url> first.", ShellSession.NO_REPOSITORY.text)
        assertEquals(Look.WARNING, ShellSession.NO_REPOSITORY.look)
    }

    @Test
    fun `scan asks for a background scan of the URL`() {
        assertEquals(ShellOutcome.Scan("github.com/acme/skills"), session().execute("/scan   github.com/acme/skills "))
        assertEquals(listOf(ShellBlock.Error("usage: /scan <url>")), blocks(session().execute("/scan")))
    }

    @Test
    fun `adopting a scan makes it current, logs it once and returns its report`() {
        val session = session()
        val blocks = session.adopt(CurrentRepository(result, similar))

        assertEquals(listOf(ShellBlock.Report(Presentation.SkillList(result))), blocks)
        assertEquals(result, session.repository?.result)
        val log = dir.resolve("state/scans.log").readLines()
        assertEquals(1, log.size)
        assertTrue(log.single().startsWith("""{"scanned_at":"2026-09-30T10:28:00Z","repository":"acme/skills""""), log.single())
    }

    @Test
    fun `a scan log that can't be written is a warning`() {
        val blocked = dir.resolve("blocked").also { it.writeText("a file") }
        val session = ShellSession(scanner, ScanLog(blocked.resolve("scans.log")), stars, clock) { _, _ -> error("unused") }

        val blocks = session.adopt(CurrentRepository(result, similar))

        assertEquals(2, blocks.size)
        val warning = assertIs<ShellBlock.Message>(blocks[1])
        assertEquals(Look.WARNING, warning.look)
        assertTrue(warning.text.startsWith("warning: could not write scan log "), warning.text)
    }

    @Test
    fun `filter lists matching skills, and text without a slash filters too`() {
        val session = scanned()

        assertEquals(listOf(ShellBlock.Report(Presentation.SkillList(result, "tests"))), blocks(session.execute("/filter tests")))
        assertEquals(listOf(ShellBlock.Report(Presentation.SkillList(result, "run tests"))), blocks(session.execute("run tests")))
        assertEquals(listOf(ShellBlock.Report(Presentation.SkillList(result, null))), blocks(session.execute("/filter")))
    }

    @Test
    fun `skill and similar show one skill, by name or path`() {
        val session = scanned()

        val detail = assertIs<ShellBlock.Report>(blocks(session.execute("/skill MPS-TESTS")).single()).presentation
        assertEquals("skills/mps-tests", assertIs<Presentation.SkillDetail>(detail).skill.path)
        val table = assertIs<ShellBlock.Similar>(blocks(session.execute("/similar skills/mps-run/")).single())
        assertEquals("mps-run", table.detail.skill.name)
        assertEquals(listOf("mps-tests"), table.detail.similar.map { it.name })
    }

    @Test
    fun `skill errors are shown inline`() {
        val session = scanned()

        assertEquals(listOf(ShellBlock.Error("no skill 'nope' in acme/skills")), blocks(session.execute("/skill nope")))
        assertEquals(listOf(ShellBlock.Error("usage: /similar <name-or-path>")), blocks(session.execute("/similar ")))
        assertEquals(listOf(ShellBlock.Error("usage: /skill <name-or-path>")), blocks(session.execute("/skill")))
    }

    @Test
    fun `browse, repo, help and quit`() {
        val session = scanned()

        val browse = assertIs<ShellOutcome.Browse>(session.execute("/browse"))
        assertEquals(result, browse.state.result)
        assertEquals(listOf(ShellBlock.Repository(result)), blocks(session.execute("/repo")))
        assertEquals(listOf(ShellBlock.Help), blocks(session.execute("/HELP")))
        assertEquals(ShellOutcome.Quit, session.execute("/quit"))
    }

    @Test
    fun `star and unstar change the stars file and say what they did`() {
        val session = scanned()

        val starred = assertIs<Presentation.StarChanged>(assertIs<ShellBlock.Report>(blocks(session.execute("/star MPS-TESTS")).single()).presentation)
        assertEquals("Starred mps-tests (acme/skills:skills/mps-tests).", starred.message)
        assertEquals(listOf("acme/skills:skills/mps-tests"), stars.read().map { it.id })
        val again = assertIs<ShellBlock.Report>(blocks(session.execute("/star skills/mps-tests")).single()).presentation
        assertEquals("mps-tests is already starred (acme/skills:skills/mps-tests).", assertIs<Presentation.StarChanged>(again).message)

        val unstarred = assertIs<ShellBlock.Report>(blocks(session.execute("/unstar mps-tests")).single()).presentation
        assertEquals("Unstarred mps-tests (acme/skills:skills/mps-tests).", assertIs<Presentation.StarChanged>(unstarred).message)
        assertEquals(emptyList(), stars.read())

        assertEquals(listOf(ShellBlock.Error("no skill 'nope' in acme/skills")), blocks(session.execute("/star nope")))
        assertEquals(listOf(ShellBlock.Error("usage: /star <name-or-path>")), blocks(session.execute("/star")))
        assertEquals(listOf(ShellBlock.Error("usage: /unstar <name-or-path>")), blocks(session.execute("/unstar ")))
    }

    @Test
    fun `stars lists every star, even without a repository`() {
        val session = session()
        assertEquals(listOf(ShellBlock.Report(Presentation.StarList(emptyList()))), blocks(session.execute("/stars")))

        stars.star("other/repo", Skill("docx", "", "skills/docx"))
        assertEquals(
            listOf(ShellBlock.Report(Presentation.StarList(listOf(Star("other/repo", "skills/docx", "docx"))))),
            blocks(session.execute("/stars")),
        )
    }

    @Test
    fun `skills are shown with stars changed elsewhere`() {
        val session = scanned()
        // As if starred in the web view or by another process after the scan.
        stars.star("ACME/skills", skills[2])

        val list = assertIs<Presentation.SkillList>(assertIs<ShellBlock.Report>(blocks(session.execute("/filter is:starred")).single()).presentation)
        assertEquals(listOf("commits"), list.skills.map { it.name })
        val detail = assertIs<Presentation.SkillDetail>(assertIs<ShellBlock.Report>(blocks(session.execute("/skill commits")).single()).presentation)
        assertTrue(detail.skill.starred)
        val browse = assertIs<ShellOutcome.Browse>(session.execute("/browse"))
        assertEquals(listOf(false, false, true), browse.state.result.skills.map { it.starred })
    }

    @Test
    fun `a stars file that can't be read warns in yellow and shows no stars`() {
        val session = scanned()
        val file = dir.resolve("data/stars.json").also { it.parent.createDirectories(); it.writeText("{broken") }

        val blocks = blocks(session.execute("/filter"))

        assertEquals(ShellBlock.Message("warning: could not read stars $file: not valid stars JSON", Look.WARNING), blocks.first())
        assertEquals(Presentation.SkillList(result, null), assertIs<ShellBlock.Report>(blocks.last()).presentation)
        assertEquals(listOf(ShellBlock.Error("could not read stars $file: not valid stars JSON")), blocks(session.execute("/stars")))
        assertEquals(listOf(ShellBlock.Error("could not read stars $file: not valid stars JSON")), blocks(session.execute("/star commits")))
    }

    @Test
    fun `unknown commands point to help`() {
        assertEquals(listOf(ShellBlock.Error("unknown command /nope; type /help for the list")), blocks(session().execute("/nope x")))
        assertEquals(listOf(ShellBlock.Error("unknown command /; type /help for the list")), blocks(session().execute("/")))
    }

    @Test
    fun `log shows the last 10 scans, oldest first`() {
        val session = session()
        assertEquals(listOf(ShellBlock.Message("No scans logged yet.")), blocks(session.execute("/log")))

        repeat(12) { i ->
            scanLog.append(ScanLogEntry("2026-09-30T10:${"%02d".format(i)}:00Z", "acme/r$i", null, "main", sha, i))
        }
        dir.resolve("state/scans.log").toFile().appendText("not json\n")

        val log = assertIs<ShellBlock.Log>(blocks(session.execute("/log")).single())
        assertEquals((2..11).map { "acme/r$it" }, log.entries.map { it.repository })
    }

    @Test
    fun `serve starts, reports and stops the web view`() {
        val session = scanned()

        val url = assertIs<ShellBlock.Message>(blocks(session.execute("/serve 0")).single()).text
        assertTrue(Regex("Skill Atlas web view: http://127\\.0\\.0\\.1:\\d+/").matches(url), url)
        val running = url.removePrefix("Skill Atlas web view: ")
        assertEquals(listOf(ShellBlock.Message("The web view is already running: $running")), blocks(session.execute("/serve")))
        assertEquals(listOf(ShellBlock.Message("Stopped the web view.")), blocks(session.execute("/serve stop")))
        assertEquals(listOf(ShellBlock.Message("The web view isn't running.")), blocks(session.execute("/serve stop")))
    }

    @Test
    fun `serve rejects bad ports and reports a taken one`() {
        for (input in listOf("/serve 70000", "/serve -1", "/serve now")) {
            assertEquals(listOf(ShellBlock.Error("usage: /serve [port] or /serve stop")), blocks(session().execute(input)), input)
        }
        ServerSocket(0).use { taken ->
            val session = session { _, _ -> throw IOException("Address already in use") }
            assertEquals(
                listOf(ShellBlock.Error("could not listen on 127.0.0.1:${taken.localPort}: Address already in use")),
                blocks(session.execute("/serve ${taken.localPort}")),
            )
        }
    }

    @Test
    fun `closing the session stops the web view`() {
        var port = 0
        val session = session { p, warn -> WebServer(scanner, scanLog, stars, clock, warn).start(p).also { port = it.port } }
        session.execute("/serve 0")
        session.close()

        assertTrue(runCatching { java.net.Socket("127.0.0.1", port).close() }.isFailure)
    }

    // ---- Printing into the scrollback ----

    private fun plain(text: String) = text.replace(Regex("\u001B\\[[0-9;]*m"), "").lines().joinToString("\n") { it.trimEnd() }

    @Test
    fun `each block is printed into the scrollback once, and only the prompt stays live`() = runBlocking {
        val shell = ShellController(scanned())
        runMosaicTest(MosaicSnapshots) {
            setContent { ShellApp(shell) }
            for (key in "/repo") sendKeyEvent(KeyboardEvent(key.code))
            sendKeyEvent(KeyboardEvent(13))
            // Keys are handled on one frame; the scrollback is printed once the composition applies.
            awaitSnapshot()
            while (shell.pending.isNotEmpty()) awaitSnapshot()
            val printed = plain(static().orEmpty().replace("\r\n", "\n"))

            assertEquals(
                """
                ❯ /repo
                  SKILL ATLAS

                 Repository   acme/skills
                 Description  Acme agent skills
                 Commit       $sha  main

                 3 skills found

                """.trimIndent(),
                printed,
            )
            assertEquals(emptyList(), shell.pending)
            assertEquals("❯  type / for commands", draw().render(AnsiLevel.NONE, false).trimEnd())
            assertEquals(null, static())
        }
    }

    @Test
    fun `similar, log, messages and errors look like the spec`() {
        fun render(block: ShellBlock): String = runBlocking {
            var text = ""
            runMosaicTest { text = setContentAndSnapshot { ShellBlockView(block, 100) } }
            text.lines().joinToString("\n") { it.trimEnd() }
        }

        val detail = Presentation.detail(result, "mps-tests")
        assertEquals(
            " Similar to mps-tests  skills/mps-tests\n" +
                "   mps-run  ████░░░░░░  37 %  skills/mps-run",
            render(ShellBlock.Similar(detail)),
        )
        assertEquals(
            " 2026-09-30T10:28:00Z  acme/skills  49d37b63488a  main    3 skills\n" +
                " 2026-09-30T10:29:00Z  acme/other   49d37b63488a  master  1 skill",
            render(
                ShellBlock.Log(
                    listOf(
                        ScanLogEntry("2026-09-30T10:28:00Z", "acme/skills", null, "main", sha, 3),
                        ScanLogEntry("2026-09-30T10:29:00Z", "acme/other", null, "master", sha, 1),
                    ),
                ),
            ),
        )
        assertEquals(" error: no skill 'x' in acme/skills", render(ShellBlock.Error("no skill 'x' in acme/skills")))
        assertEquals("❯ /skill x", render(ShellBlock.Echo("/skill x")))
        assertEquals(" No repository yet — run /scan <url> first.", render(ShellSession.NO_REPOSITORY))
        assertTrue(render(ShellBlock.Help).contains("   /similar <name-or-path>  Show the skills most similar to one skill"))
    }
}

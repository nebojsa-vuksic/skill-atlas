package skillatlas

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** End-to-end tests of `skill-atlas scan` against a stubbed GitHub API and a local git repository. */
class SkillAtlasCliTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var api: HttpServer
    private val apiResponses = mutableMapOf<String, ApiResponse>()

    private lateinit var origin: Path
    private lateinit var tempRoot: Path
    private lateinit var logFile: Path
    private val starsFile: Path get() = dir.resolve("data/skill-atlas/stars.json")

    private data class ApiResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    @BeforeTest
    fun setUp() {
        api = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        api.createContext("/") { exchange ->
            val response = apiResponses[exchange.requestURI.path] ?: ApiResponse(404, """{"message":"Not Found"}""")
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        api.start()

        origin = dir.resolve("origin")
        tempRoot = dir.resolve("tmp").createDirectories()
        logFile = dir.resolve("state/skill-atlas/scans.log")
    }

    @AfterTest
    fun tearDown() = api.stop(0)

    private fun stubRepository(fullName: String = "acme/skills", description: String? = "Acme skills", branch: String = "main") {
        val descriptionJson = description?.let { "\"$it\"" } ?: "null"
        apiResponses["/repos/$fullName"] = ApiResponse(
            200,
            """{"full_name":"$fullName","description":$descriptionJson,"default_branch":"$branch","private":false}""",
        )
    }

    private fun createOrigin(files: Map<String, String>): String {
        origin.createDirectories()
        git(origin, "init", "--quiet", "--initial-branch=main")
        for ((path, content) in files) {
            origin.resolve(path).also { it.parent.createDirectories() }.writeText(content)
        }
        git(origin, "add", "--all")
        git(origin, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
            "commit", "--quiet", "--allow-empty", "-m", "fixture")
        return git(origin, "rev-parse", "HEAD").trim()
    }

    private fun git(workDir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(workDir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private fun run(
        vararg args: String,
        gitExecutable: String = "git",
        logPath: Path = logFile,
        interactive: Boolean = false,
        shellView: (ShellSession) -> Unit = { error("the shell needs a terminal") },
    ): Run {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val err = PrintStream(stderr, true)
        val scanner = Scanner(
            github = GitHubClient(apiBaseUrl = "http://127.0.0.1:${api.address.port}"),
            git = Git(executable = gitExecutable),
            cloneUrl = { origin.toUri().toString() },
            tempRoot = tempRoot,
        )
        val out = PrintStream(stdout, true)
        val clock = Clock.fixed(Instant.parse("2026-09-30T10:28:00Z"), ZoneOffset.UTC)
        val cli = SkillAtlasCli(scanner, ScanLog(logPath), StarStore(starsFile), PlainScanView(out, err), out, err, clock, interactive, shellView)
        val exitCode = cli.run(args.toList())
        return Run(exitCode, stdout.toString(), stderr.toString())
    }

    private fun assertTempDirectoryRemoved() = assertEquals(emptyList(), tempRoot.listDirectoryEntries())

    @Test
    fun `scans a repository and logs the scan`() {
        stubRepository()
        val commit = createOrigin(
            mapOf(
                "skills/pdf/SKILL.md" to "---\nname: pdf-extract\ndescription: Extract text from PDFs.\n---\n",
                "skills/broken/SKILL.md" to "---\nname: [oops\n---\n",
                "skills/csv/SKILL.md" to "---\nname: csv-tools\n---\n",
                "node_modules/dep/SKILL.md" to "---\nname: ignored\ndescription: no\n---\n",
                "README.md" to "# Acme\n",
            ),
        )

        val run = run("scan", "https://github.com/acme/skills")

        assertEquals(ExitCode.OK, run.exitCode, run.stderr)
        assertEquals(
            """
            Repository:  acme/skills
            Description: Acme skills
            Commit:      $commit (main)

            Found 3 skills:

              broken  [warning: invalid frontmatter]
                (no description)
                skills/broken

              csv-tools  [warning: missing description]
                (no description)
                skills/csv

              pdf-extract
                Extract text from PDFs.
                skills/pdf

            """.trimIndent(),
            run.stdout,
        )
        assertTrue(run.stderr.contains("Cloning acme/skills (main)..."), run.stderr)
        assertEquals(
            listOf(
                """{"scanned_at":"2026-09-30T10:28:00Z","repository":"acme/skills","description":"Acme skills","branch":"main","commit":"$commit","skill_count":3}""",
            ),
            logFile.readLines(),
        )
        assertTempDirectoryRemoved()
    }

    @Test
    fun `accepts every URL form`() {
        stubRepository()
        createOrigin(mapOf("SKILL.md" to "---\nname: root\ndescription: d\n---\n"))

        for (url in listOf("github.com/acme/skills", "git@github.com:acme/skills.git", "http://github.com/acme/skills/")) {
            val run = run("scan", url)
            assertEquals(ExitCode.OK, run.exitCode, run.stderr)
            assertTrue(run.stdout.startsWith("Repository:  acme/skills\n"), run.stdout)
        }
        assertEquals(3, logFile.readLines().size)
    }

    @Test
    fun `reports a repository without skills`() {
        stubRepository(description = null)
        createOrigin(mapOf("README.md" to "nothing here"))

        val run = run("scan", "https://github.com/acme/skills")

        assertEquals(ExitCode.OK, run.exitCode, run.stderr)
        assertTrue(run.stdout.contains("Description: (none)\n"), run.stdout)
        assertTrue(run.stdout.endsWith("\n\nNo skills found.\n"), run.stdout)
    }

    @Test
    fun `rejects an invalid URL with exit code 2`() {
        val run = run("scan", "https://gitlab.com/a/b")

        assertEquals(ExitCode.USAGE, run.exitCode)
        assertEquals("error: not a GitHub repository URL: https://gitlab.com/a/b\n", run.stderr)
        assertEquals("", run.stdout)
        assertTrue(!logFile.exists())
    }

    @Test
    fun `reports a missing repository with exit code 3`() {
        val run = run("scan", "https://github.com/acme/missing")

        assertEquals(ExitCode.REPOSITORY_NOT_FOUND, run.exitCode)
        assertTrue(
            run.stderr.endsWith("error: repository acme/missing not found (is it private? set GITHUB_TOKEN)\n"),
            run.stderr,
        )
        assertTrue(!logFile.exists())
    }

    @Test
    fun `reports a repository that cannot be cloned with exit code 3`() {
        stubRepository()

        val run = run("scan", "https://github.com/acme/skills")

        assertEquals(ExitCode.REPOSITORY_NOT_FOUND, run.exitCode, run.stderr)
        assertTempDirectoryRemoved()
    }

    @Test
    fun `reports a missing default branch with exit code 4`() {
        stubRepository(branch = "develop")
        createOrigin(mapOf("SKILL.md" to "---\nname: a\ndescription: b\n---\n"))

        val run = run("scan", "https://github.com/acme/skills")

        assertEquals(ExitCode.BRANCH_NOT_FOUND, run.exitCode, run.stderr)
        assertTrue(
            run.stderr.endsWith("error: branch 'develop' not found in acme/skills (is the repository empty?)\n"),
            run.stderr,
        )
        assertTempDirectoryRemoved()
    }

    @Test
    fun `reports rate limiting with exit code 5`() {
        apiResponses["/repos/acme/skills"] =
            ApiResponse(403, """{"message":"API rate limit exceeded"}""", mapOf("x-ratelimit-remaining" to "0"))

        val run = run("scan", "https://github.com/acme/skills")

        assertEquals(ExitCode.NETWORK, run.exitCode)
        assertTrue(
            run.stderr.endsWith("error: GitHub API rate limit exceeded; set GITHUB_TOKEN to raise the limit\n"),
            run.stderr,
        )
    }

    @Test
    fun `reports an unreachable API with exit code 5`() {
        api.stop(0)

        val run = run("scan", "https://github.com/acme/skills")

        assertEquals(ExitCode.NETWORK, run.exitCode)
        assertTrue(run.stderr.contains("error: could not reach the GitHub API"), run.stderr)
    }

    @Test
    fun `reports a missing git executable with exit code 1`() {
        val run = run("scan", "https://github.com/acme/skills", gitExecutable = "git-that-does-not-exist")

        assertEquals(ExitCode.INTERNAL_ERROR, run.exitCode)
        assertEquals("error: git executable not found on PATH; install git and try again\n", run.stderr)
    }

    @Test
    fun `still succeeds when the scan log cannot be written`() {
        stubRepository()
        createOrigin(mapOf("SKILL.md" to "---\nname: a\ndescription: b\n---\n"))
        val blocked = dir.resolve("blocked").also { it.writeText("a file, not a directory") }

        val run = run("scan", "https://github.com/acme/skills", logPath = blocked.resolve("scans.log"))

        assertEquals(ExitCode.OK, run.exitCode)
        assertTrue(run.stdout.contains("Found 1 skill:"), run.stdout)
        assertTrue(run.stderr.contains("warning: could not write scan log"), run.stderr)
    }

    @Test
    fun `prints help and version with exit code 0`() {
        val help = run("--help")
        assertEquals(ExitCode.OK, help.exitCode)
        assertTrue(help.stdout.contains("scan"), help.stdout)

        val scanHelp = run("scan", "-h")
        assertEquals(ExitCode.OK, scanHelp.exitCode)
        assertTrue(scanHelp.stdout.contains("<github-project-url>"), scanHelp.stdout)

        val version = run("--version")
        assertEquals(ExitCode.OK, version.exitCode)
        assertTrue(version.stdout.startsWith("skill-atlas "), version.stdout)
    }

    @Test
    fun `shell needs a terminal`() {
        val run = run("shell")

        assertEquals(ExitCode.USAGE, run.exitCode)
        assertEquals("", run.stdout)
        assertEquals("error: shell needs an interactive terminal; use \"skill-atlas scan\" instead\n", run.stderr)
    }

    @Test
    fun `no arguments open the shell in a terminal`() {
        var opened = 0
        val run = run(interactive = true, shellView = { session ->
            opened++
            assertEquals(null, session.repository)
        })

        assertEquals(ExitCode.OK, run.exitCode, run.stderr)
        assertEquals(1, opened)
        assertEquals(ExitCode.OK, run("shell", interactive = true, shellView = { opened++ }).exitCode)
        assertEquals(2, opened)
    }

    @Test
    fun `treats bad usage as exit code 2`() {
        for (args in listOf(emptyList(), listOf("scan"), listOf("scan", "--nope", "x"), listOf("frobnicate"))) {
            val run = run(*args.toTypedArray())
            assertEquals(ExitCode.USAGE, run.exitCode, "args=$args")
            assertEquals("", run.stdout, "args=$args")
        }
    }
}

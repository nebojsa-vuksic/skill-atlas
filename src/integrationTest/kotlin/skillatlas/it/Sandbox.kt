package skillatlas.it

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** The installed launcher under test, passed in by Gradle. */
val LAUNCHER: Path = Path.of(checkNotNull(System.getProperty("skillAtlas.launcher")) { "skillAtlas.launcher not set" })

/** The version Gradle built, so `--version` can be asserted exactly. */
val VERSION: String = checkNotNull(System.getProperty("skillAtlas.version")) { "skillAtlas.version not set" }

class CliRun(val exitCode: Int, val stdout: String, val stderr: String) {
    override fun toString() = "exit=$exitCode\n--- stdout\n$stdout\n--- stderr\n$stderr"
}

/**
 * Everything one test needs to run the real CLI deterministically (spec section 11.2):
 * a stub GitHub API, local fixture repositories, and isolated state and temp directories.
 */
class Sandbox(private val root: Path) : AutoCloseable {
    private val repositories = root.resolve("repositories").createDirectories()
    private val workTrees = root.resolve("work").createDirectories()
    private val stateHome = root.resolve("state").createDirectories()
    private val tempDir = root.resolve("tmp").createDirectories()

    val api = StubGitHubApi()

    val scanLog: Path get() = stateHome.resolve("skill-atlas/scans.log")

    fun scanLogLines(): List<String> = if (scanLog.exists()) scanLog.readLines() else emptyList()

    /** Clone directories the CLI left behind in its temp directory. */
    fun leftoverCloneDirectories(): List<String> =
        tempDir.listDirectoryEntries().map { it.name }.filter { it.startsWith("skill-atlas-") }

    /**
     * Creates `owner/name` with one commit containing [files] and returns its SHA. The
     * author, committer and dates are fixed, so the same files always give the same SHA.
     */
    fun createRepository(fullName: String, files: Map<String, String>): String {
        val work = workTrees.resolve(fullName).createDirectories()
        git(work, "init", "--quiet", "--initial-branch=main")
        for ((path, content) in files) {
            work.resolve(path).also { it.parent.createDirectories() }.writeText(content)
        }
        git(work, "add", "--all")
        git(work, "commit", "--quiet", "--allow-empty", "--message", "Fixture")
        git(root, "clone", "--quiet", "--bare", work.toString(), bareRepository(fullName).toString())
        return git(work, "rev-parse", "HEAD").trim()
    }

    /** Creates `owner/name` as a repository without any commits. */
    fun createEmptyRepository(fullName: String) {
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", bareRepository(fullName).toString())
    }

    private fun bareRepository(fullName: String) = repositories.resolve("$fullName.git")

    /** Runs the CLI with stdout and stderr connected to pipes. */
    fun run(vararg args: String): CliRun = execute(listOf(LAUNCHER.toString(), *args))

    /**
     * Runs the CLI inside a 100×40 pseudo-terminal. The terminal output, where stdout and
     * stderr are mixed as a user would see them, is returned as [CliRun.stdout].
     */
    fun runInTerminal(vararg args: String, interruptAfter: String? = null): CliRun {
        val helper = root.resolve("pty_run.py")
        if (!helper.exists()) {
            javaClass.getResourceAsStream("/pty_run.py")!!.use { Files.copy(it, helper) }
        }
        val command = buildList {
            addAll(listOf("python3", helper.toString(), "--cols", "100", "--rows", "40"))
            if (interruptAfter != null) addAll(listOf("--interrupt-after", interruptAfter))
            add("--")
            add(LAUNCHER.toString())
            addAll(args)
        }
        return execute(command)
    }

    private fun execute(command: List<String>): CliRun {
        val process = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply { environment().apply { clear(); putAll(cliEnvironment()) } }
            .start()
        var stdout = ""
        var stderr = ""
        val readers = listOf(
            thread { stdout = readFully(process.inputStream) },
            thread { stderr = readFully(process.errorStream) },
        )
        check(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "${command.joinToString(" ")} did not finish within $PROCESS_TIMEOUT_SECONDS seconds"
        }
        readers.forEach { it.join() }
        return CliRun(process.exitValue(), stdout, stderr)
    }

    private fun cliEnvironment(): Map<String, String> {
        val inherited = System.getenv().filterKeys { it in INHERITED_VARIABLES }
        return inherited + FIXED_GIT_ENVIRONMENT + mapOf(
            "SKILL_ATLAS_GITHUB_API_URL" to api.url,
            "SKILL_ATLAS_GIT_BASE_URL" to repositories.toUri().toString().trimEnd('/'),
            "XDG_STATE_HOME" to stateHome.toString(),
            "JAVA_OPTS" to "-Djava.io.tmpdir=$tempDir",
            "TERM" to "xterm-256color",
        )
    }

    private fun git(workDir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .apply { environment().putAll(FIXED_GIT_ENVIRONMENT) }
            .start()
        val output = readFully(process.inputStream)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    override fun close() = api.close()

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 120L

        /** Only what the launcher needs to find a JVM and basic tools. Notably not GITHUB_TOKEN. */
        val INHERITED_VARIABLES = setOf("PATH", "HOME", "USER", "LANG", "JAVA_HOME", "SYSTEMROOT")

        /** Ignores the developer's git configuration and pins identities and dates. */
        val FIXED_GIT_ENVIRONMENT = mapOf(
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_AUTHOR_NAME" to "Skill Atlas Fixture",
            "GIT_AUTHOR_EMAIL" to "fixture@skill-atlas.invalid",
            "GIT_AUTHOR_DATE" to "2026-01-01T00:00:00+0000",
            "GIT_COMMITTER_NAME" to "Skill Atlas Fixture",
            "GIT_COMMITTER_EMAIL" to "fixture@skill-atlas.invalid",
            "GIT_COMMITTER_DATE" to "2026-01-01T00:00:00+0000",
        )

        fun readFully(stream: InputStream): String = try {
            stream.readBytes().toString(Charsets.UTF_8)
        } catch (_: IOException) {
            ""
        }
    }
}

/** A stand-in for `api.github.com` that serves canned responses on 127.0.0.1. */
class StubGitHubApi : AutoCloseable {
    private class Response(val status: Int, val body: String, val headers: Map<String, String>, val hold: CountDownLatch?)

    private val responses = mutableMapOf<String, Response>()
    private val held = mutableListOf<CountDownLatch>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@StubGitHubApi.executor
        createContext("/") { exchange ->
            val response = synchronized(responses) { responses[exchange.requestURI.path] }
                ?: Response(404, """{"message":"Not Found"}""", emptyMap(), null)
            response.hold?.await()
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = response.body.toByteArray()
            try {
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: IOException) {
                // The client gave up, e.g. because the scan was interrupted.
            }
        }
        start()
    }

    val url: String = "http://127.0.0.1:${server.address.port}"

    fun repository(fullName: String, description: String?, defaultBranch: String = "main") {
        val descriptionJson = description?.let { "\"$it\"" } ?: "null"
        respond(
            "/repos/$fullName",
            200,
            """{"full_name":"$fullName","description":$descriptionJson,"default_branch":"$defaultBranch","private":false}""",
        )
    }

    fun rateLimited(fullName: String) = respond(
        "/repos/$fullName",
        403,
        """{"message":"API rate limit exceeded"}""",
        mapOf("x-ratelimit-remaining" to "0"),
    )

    /** Never answers requests for [fullName] until the stub is closed. */
    fun hang(fullName: String) {
        val latch = CountDownLatch(1)
        synchronized(held) { held += latch }
        synchronized(responses) { responses["/repos/$fullName"] = Response(503, "", emptyMap(), latch) }
    }

    private fun respond(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        synchronized(responses) { responses[path] = Response(status, body, headers, null) }
    }

    override fun close() {
        synchronized(held) { held.forEach { it.countDown() } }
        server.stop(0)
        executor.shutdownNow()
    }
}

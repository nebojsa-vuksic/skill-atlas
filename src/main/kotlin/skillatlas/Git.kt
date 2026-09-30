package skillatlas

import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the `git` executable to fetch repository contents (spec section 4.1, steps 3–4). */
class Git(
    private val executable: String = "git",
    private val token: String? = null,
    private val baseEnvironment: Map<String, String> = System.getenv(),
) {
    fun ensureAvailable() {
        val result = try {
            run(listOf(executable, "--version"))
        } catch (e: IOException) {
            throw GitNotFoundException(e)
        }
        if (result.exitCode != 0) throw GitNotFoundException()
    }

    /**
     * Fetches the latest commit of [branch] from [url] into [destination], which must not
     * exist yet. Only `SKILL.md` files are checked out: the clone is shallow, skips file
     * contents up front (`--filter=blob:none`), and then downloads just the skill files
     * through a sparse checkout. For large repositories this is many times faster than a
     * full checkout. Servers without partial clone support simply send everything.
     */
    fun shallowClone(url: String, branch: String, destination: Path, repository: RepoCoordinates) {
        val environment = cloneEnvironment(url)
        val clone = listOf(
            executable, "clone",
            "--depth", "1", "--branch", branch, "--single-branch", "--no-tags", "--quiet",
            "--filter=blob:none", "--no-checkout",
            "--", url, destination.toString(),
        )
        run(clone, environment).failIfUnsuccessful(branch, repository)

        val git = listOf(executable, "-C", destination.toString())
        run(git + listOf("sparse-checkout", "set", "--no-cone", SkillScanner.SKILL_FILE_NAME), environment)
            .failIfUnsuccessful(branch, repository)
        run(git + listOf("checkout", "--quiet"), environment).failIfUnsuccessful(branch, repository)
    }

    private fun ProcessResult.failIfUnsuccessful(branch: String, repository: RepoCoordinates) {
        if (exitCode == 0) return
        throw when {
            stderr.contains("Remote branch", ignoreCase = true) && stderr.contains("not found", ignoreCase = true) ->
                BranchNotFoundException(branch, repository)
            REPOSITORY_MISSING_MARKERS.any { stderr.contains(it, ignoreCase = true) } ->
                RepositoryNotFoundException(repository)
            else -> NetworkException("git clone failed: ${lastLine(stderr)}")
        }
    }

    fun headCommit(repositoryDir: Path): String {
        val result = run(listOf(executable, "-C", repositoryDir.toString(), "rev-parse", "HEAD"))
        val sha = result.stdout.trim()
        check(result.exitCode == 0 && SHA.matches(sha)) { "git rev-parse HEAD failed: ${lastLine(result.stderr)}" }
        return sha
    }

    private fun cloneEnvironment(url: String): Map<String, String> {
        val environment = mutableMapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_LFS_SKIP_SMUDGE" to "1",
        )
        if (token != null && url.startsWith("https://github.com/")) {
            // Passed through GIT_CONFIG_* rather than `-c` so the token never shows up in process arguments.
            val index = baseEnvironment["GIT_CONFIG_COUNT"]?.toIntOrNull() ?: 0
            val credentials = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
            environment["GIT_CONFIG_COUNT"] = (index + 1).toString()
            environment["GIT_CONFIG_KEY_$index"] = "http.https://github.com/.extraHeader"
            environment["GIT_CONFIG_VALUE_$index"] = "Authorization: Basic $credentials"
        }
        return environment
    }

    private fun run(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult {
        val process = ProcessBuilder(command)
            .apply { environment().putAll(environment) }
            .start()
        process.outputStream.close()

        var stdout = ""
        var stderr = ""
        val readers = listOf(
            thread { stdout = readFully(process.inputStream) },
            thread { stderr = readFully(process.errorStream) },
        )
        val finished = try {
            process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }
        if (!finished) {
            process.destroyForcibly()
            throw NetworkException("git timed out after $TIMEOUT_MINUTES minutes")
        }
        readers.forEach { it.join() }
        return ProcessResult(process.exitValue(), stdout, stderr)
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val TIMEOUT_MINUTES = 10L
        val SHA = Regex("[0-9a-f]{40}")
        val REPOSITORY_MISSING_MARKERS = listOf(
            "Repository not found",
            "does not appear to be a git repository",
            "could not read Username",
            "Authentication failed",
        )

        /** Reads [stream] to the end. Returns what was read so far if the process is killed mid-read. */
        fun readFully(stream: InputStream): String {
            val text = StringBuilder()
            try {
                stream.bufferedReader().use { reader -> reader.forEachLine { text.appendLine(it) } }
            } catch (_: IOException) {
                // The stream was closed because the process was destroyed.
            }
            return text.toString()
        }

        fun lastLine(text: String) = text.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "no output"
    }
}

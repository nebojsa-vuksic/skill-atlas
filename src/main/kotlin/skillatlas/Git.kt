package skillatlas

import java.io.IOException
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

    /** Shallow-clones [branch] of [url] into [destination], which must not exist yet. */
    fun shallowClone(url: String, branch: String, destination: Path, repository: RepoCoordinates) {
        val command = listOf(
            executable, "clone",
            "--depth", "1", "--branch", branch, "--single-branch", "--no-tags", "--quiet",
            "--", url, destination.toString(),
        )
        val result = run(command, cloneEnvironment(url))
        if (result.exitCode == 0) return

        val stderr = result.stderr
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
            thread { stdout = process.inputStream.bufferedReader().readText() },
            thread { stderr = process.errorStream.bufferedReader().readText() },
        )
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
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

        fun lastLine(text: String) = text.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "no output"
    }
}

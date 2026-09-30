package skillatlas

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** Runs the scan pipeline from spec section 4.1. */
class Scanner(
    private val github: GitHubClient,
    private val git: Git,
    private val cloneUrl: (RepoCoordinates) -> String = { "https://github.com/${it.owner}/${it.name}.git" },
    private val tempRoot: Path? = null,
    private val progress: (String) -> Unit = {},
) {
    fun scan(url: String): ScanResult {
        val repository = GitHubUrl.parse(url)
        git.ensureAvailable()

        progress("Fetching metadata for $repository...")
        val metadata = github.fetchRepository(repository)
        val branch = metadata.defaultBranch

        return withTempDirectory { tempDir ->
            val checkout = tempDir.resolve("repo")
            progress("Cloning ${metadata.fullName} ($branch)...")
            git.shallowClone(cloneUrl(repository), branch, checkout, repository)
            val commit = git.headCommit(checkout)
            val skills = SkillScanner.discover(checkout).map { SkillParser.parse(checkout, it, repository.name) }
            ScanResult(metadata, branch, commit, skills)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun <T> withTempDirectory(block: (Path) -> T): T {
        val dir = if (tempRoot != null) {
            Files.createTempDirectory(tempRoot, "skill-atlas-")
        } else {
            Files.createTempDirectory("skill-atlas-")
        }
        fun cleanUp() = try {
            dir.deleteRecursively()
        } catch (e: IOException) {
            progress("warning: could not remove temporary directory $dir: ${e.message}")
        }

        // Also clean up when the scan is interrupted, e.g. with Ctrl-C.
        val hook = Thread(::cleanUp)
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            return block(dir)
        } finally {
            cleanUp()
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // The JVM is already shutting down, and the hook will run anyway.
            }
        }
    }
}

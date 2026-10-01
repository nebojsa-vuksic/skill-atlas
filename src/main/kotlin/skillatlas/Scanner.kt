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
) {
    /** Scans the repository at [url], reporting each step through [progress]. An owner URL is rejected (spec section 5.11). */
    fun scan(url: String, progress: (String) -> Unit = {}): ScanResult {
        val repository = when (val target = GitHubUrl.target(url)) {
            is ScanTarget.Repository -> target.coordinates
            is ScanTarget.Owner -> throw OwnerUrlNotSupportedException(url)
        }
        git.ensureAvailable()

        progress("Fetching metadata for $repository...")
        return clone(repository, github.fetchRepository(repository), progress)
    }

    /** Scans a repository whose metadata is already known from its owner's repository list (spec section 5.11, step 5). */
    fun scan(listed: RepositoryMetadata, progress: (String) -> Unit = {}): ScanResult {
        git.ensureAvailable()
        return clone(coordinatesOf(listed), listed, progress)
    }

    /** Finds the repositories of the organization or user [login] that may have skills (spec section 5.11). */
    fun discoverOwner(login: String, progress: (String) -> Unit = {}): OwnerDiscovery = OwnerSearch(github).discover(login, progress)

    private fun clone(repository: RepoCoordinates, metadata: RepositoryMetadata, progress: (String) -> Unit): ScanResult {
        val branch = metadata.defaultBranch
        return withTempDirectory(progress) { tempDir ->
            val checkout = tempDir.resolve("repo")
            progress("Cloning ${metadata.fullName} ($branch)...")
            git.shallowClone(cloneUrl(repository), branch, checkout, repository)
            val commit = git.headCommit(checkout)
            val catalog = SkillCatalog.build(checkout, SkillScanner.discover(checkout), repository.name)
            ScanResult(metadata, branch, commit, catalog.skills, catalog.ignored, catalog.contents)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun <T> withTempDirectory(progress: (String) -> Unit, block: (Path) -> T): T {
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

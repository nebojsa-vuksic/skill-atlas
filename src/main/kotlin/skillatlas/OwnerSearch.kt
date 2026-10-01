package skillatlas

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** The repositories of one organization or user that may have skills (spec section 5.11, steps 1 to 4). */
data class OwnerDiscovery(
    val owner: OwnerMetadata,
    /** The repositories to scan, sorted by `owner/name` ignoring case, with their listed metadata. */
    val candidates: List<RepositoryMetadata>,
    /** How many repositories had their tree checked: every listed one except the skipped ones. */
    val checked: Int,
    /** Forks and archived repositories, which are never checked or scanned. */
    val skipped: Int,
)

/** Finds an owner's repositories with skill files through the GitHub API, without cloning them. */
class OwnerSearch(private val github: GitHubClient, private val parallelism: Int = 8) {
    fun discover(login: String, progress: (String) -> Unit = {}): OwnerDiscovery {
        progress("Listing repositories of $login...")
        val owner = github.fetchOwner(login)
        val (skipped, kept) = github.listRepositories(owner).partition { it.fork || it.archived }
        val repositories = kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fullName })

        progress("Checking ${repositories.size} ${if (repositories.size == 1) "repository" else "repositories"} of ${owner.login} for skill files...")
        val checks = check(repositories)
        return OwnerDiscovery(owner, repositories.filterIndexed { i, _ -> checks[i].mayHaveSkills }, repositories.size, skipped.size)
    }

    /** Checks every repository's tree, [parallelism] at a time. A rate limit ends all of them. */
    private fun check(repositories: List<RepositoryMetadata>): List<TreeCheck> {
        if (repositories.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(parallelism, repositories.size))
        try {
            val futures = repositories.map { repository ->
                pool.submit(Callable { github.checkSkillFiles(coordinatesOf(repository), repository.defaultBranch) })
            }
            return futures.map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

/** `owner/name` from a repository's `full_name`. */
fun coordinatesOf(repository: RepositoryMetadata): RepoCoordinates =
    RepoCoordinates(repository.fullName.substringBefore('/'), repository.fullName.substringAfter('/'))

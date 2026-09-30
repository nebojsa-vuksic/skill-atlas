package skillatlas

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** How one repository of a multi-repository scan turned out (spec section 5.10). */
sealed interface RepositoryOutcome {
    val url: String

    /** `owner/name` when the URL could be parsed, otherwise the URL as given. */
    val label: String

    data class Scanned(override val url: String, val result: ScanResult) : RepositoryOutcome {
        override val label: String get() = result.repository.fullName
    }

    data class Failed(override val url: String, override val label: String, val error: SkillAtlasException) : RepositoryOutcome
}

/** Scans several repositories, up to [parallelism] at a time, keeping the order the URLs were given in. */
class MultiScanner(private val scanner: Scanner, private val parallelism: Int = 4) {
    fun scanAll(urls: List<String>, progress: (String) -> Unit = {}): List<RepositoryOutcome> {
        val lock = Any()
        return scanAllWith(urls) { url -> scanner.scan(url) { message -> synchronized(lock) { progress(message) } } }
    }

    /** Like [scanAll], but each repository is fetched by [scan], e.g. from a cache. */
    fun scanAllWith(urls: List<String>, scan: (String) -> ScanResult): List<RepositoryOutcome> {
        // The same owner/repo given twice, in any URL form, is scanned once.
        val unique = LinkedHashMap<String, String>()
        for (url in urls) {
            val key = runCatching { GitHubUrl.parse(url).toString().lowercase() }.getOrElse { "invalid:$url" }
            unique.putIfAbsent(key, url)
        }
        if (unique.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(minOf(parallelism, unique.size))
        try {
            val futures = unique.values.map { url -> pool.submit(Callable { scanOne(url, scan) }) }
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

    private fun scanOne(url: String, scan: (String) -> ScanResult): RepositoryOutcome {
        val label = runCatching { GitHubUrl.parse(url).toString() }.getOrElse { url.trim() }
        return try {
            RepositoryOutcome.Scanned(url, scan(url))
        } catch (e: SkillAtlasException) {
            RepositoryOutcome.Failed(url, label, e)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            RepositoryOutcome.Failed(url, label, UnexpectedFailureException(e))
        }
    }
}

/** A skill's identity across repositories: `owner/repo:path` (spec section 5.10). */
fun skillId(repository: String, path: String) = "$repository:$path"

/**
 * Similar skills over the listed skills of all [results] together (spec sections 5.6 and 5.10),
 * keyed by [skillId]. The paths of the similar skills are ids too.
 */
fun crossRepositorySimilarity(results: List<ScanResult>): Map<String, List<SimilarSkill>> {
    val skills = results.flatMap { result -> result.skills.map { it.copy(path = skillId(result.repository.fullName, it.path)) } }
    val contents = results.flatMap { result ->
        result.contents.map { (path, text) -> skillId(result.repository.fullName, path) to text }
    }.toMap()
    return SkillSimilarity.compute(skills, contents)
}

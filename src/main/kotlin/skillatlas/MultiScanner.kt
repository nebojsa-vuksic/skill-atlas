package skillatlas

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** How one repository of a multi-repository scan turned out (spec section 5.10). */
sealed interface RepositoryOutcome {
    val url: String

    /** `owner/name` when the URL could be parsed, otherwise the URL as given. */
    val label: String

    /** The owner URL this repository was found through, or null when its own URL was given (spec section 5.12). */
    val from: String?

    data class Scanned(override val url: String, val result: ScanResult, override val from: String? = null) : RepositoryOutcome {
        override val label: String get() = result.repository.fullName
    }

    data class Failed(
        override val url: String,
        override val label: String,
        val error: SkillAtlasException,
        override val from: String? = null,
    ) : RepositoryOutcome
}

/** How one owner URL of a scan turned out (spec section 5.12). */
sealed interface OwnerOutcome {
    val url: String
    val label: String

    data class Searched(
        override val url: String,
        val discovery: OwnerDiscovery,
        /** The candidates that list at least one skill, in order, wherever in the report they are. */
        val withSkills: List<String>,
    ) : OwnerOutcome {
        override val label: String get() = discovery.owner.login

        /** The part of the `Searched` line after the owner's name, e.g. `: 2 of 3 repositories have skills`. */
        val counts: String
            get() {
                val checked = discovery.checked
                val noun = if (checked == 1) "repository" else "repositories"
                // "1 of 3 repositories has", "0 of 1 repository has", "0 of 3 repositories have".
                val verb = if (withSkills.size == 1 || checked == 1) "has" else "have"
                val skipped = when (val count = discovery.skipped) {
                    0 -> ""
                    1 -> " (1 fork or archived skipped)"
                    else -> " ($count forks or archived skipped)"
                }
                return ": ${withSkills.size} of $checked $noun $verb skills$skipped"
            }

        /** e.g. `Searched acme: 2 of 3 repositories have skills (1 fork or archived skipped)`. */
        val line: String get() = "Searched ${sanitize(label)}$counts"
    }

    data class Failed(override val url: String, override val label: String, val error: SkillAtlasException) : OwnerOutcome
}

/** A failed repository or owner, by its label. */
data class ScanFailure(val label: String, val error: SkillAtlasException)

/** Everything a scan of several URLs found, in the order of the URLs, with owners expanded in place (spec sections 5.10 and 5.12). */
data class MultiScan(
    val repositories: List<RepositoryOutcome>,
    val owners: List<OwnerOutcome> = emptyList(),
    /** Every failure, of repositories and of owners, in URL order. The first one sets the exit code. */
    val failures: List<ScanFailure> = repositories.filterIsInstance<RepositoryOutcome.Failed>().map { ScanFailure(it.label, it.error) },
) {
    /** What the views show: an owner's repositories without skills are left out (spec section 5.12). */
    val reported: List<RepositoryOutcome>
        get() = repositories.filterNot { it is RepositoryOutcome.Scanned && it.from != null && it.result.skills.isEmpty() }

    /** Every repository that was scanned, reported or not, each of which goes into the scan log. */
    val scanned: List<ScanResult> get() = repositories.filterIsInstance<RepositoryOutcome.Scanned>().map { it.result }
}

/**
 * Scans several repositories, up to [parallelism] at a time, keeping the order the URLs were given in.
 * Owner URLs are first expanded into their repositories with skill files (spec section 5.12).
 */
class MultiScanner(private val scanner: Scanner, private val parallelism: Int = 4) {
    fun scanAll(urls: List<String>, progress: (String) -> Unit = {}): MultiScan {
        val lock = Any()
        val report: (String) -> Unit = { message -> synchronized(lock) { progress(message) } }
        return scanAllWith(urls, discover = { login -> scanner.discoverOwner(login, report) }) { url, listed ->
            if (listed == null) scanner.scan(url, report) else scanner.scan(listed, report)
        }
    }

    /**
     * Like [scanAll], but owners are found by [discover] and each repository is fetched by [scan], e.g. from a
     * cache. [scan] gets the listed metadata for a repository found through an owner, and null otherwise.
     */
    fun scanAllWith(
        urls: List<String>,
        discover: (login: String) -> OwnerDiscovery = { scanner.discoverOwner(it) },
        scan: (url: String, listed: RepositoryMetadata?) -> ScanResult,
    ): MultiScan {
        // The same owner/repo given twice, in any URL form or through an owner, is scanned once, at its first position.
        val tasks = LinkedHashMap<String, Task>()
        val owners = LinkedHashMap<String, OwnerOutcome>()
        // Failures are reported in this order: repositories and owners as their URLs came, an owner's repositories after it.
        val order = mutableListOf<Entry>()
        for (url in urls) {
            when (val target = runCatching { GitHubUrl.target(url) }.getOrNull()) {
                is ScanTarget.Owner -> {
                    val key = target.login.lowercase()
                    if (key in owners) continue
                    val outcome = discoverOne(url, target.login, discover)
                    owners[key] = outcome
                    order += Entry(key, owner = true)
                    if (outcome is OwnerOutcome.Searched) {
                        for (listed in outcome.discovery.candidates) {
                            val repositoryKey = listed.fullName.lowercase()
                            if (repositoryKey in tasks) continue
                            tasks[repositoryKey] = Task("https://github.com/${listed.fullName}", listed, from = url)
                            order += Entry(repositoryKey, owner = false)
                        }
                    }
                }
                else -> {
                    val key = (target as? ScanTarget.Repository)?.coordinates?.toString()?.lowercase() ?: "invalid:$url"
                    if (key in tasks) continue
                    tasks[key] = Task(url, listed = null, from = null)
                    order += Entry(key, owner = false)
                }
            }
        }

        val outcomes = scanTasks(tasks, scan)
        val searched = owners.mapValues { (_, outcome) ->
            if (outcome !is OwnerOutcome.Searched) return@mapValues outcome
            val withSkills = outcome.discovery.candidates.map { it.fullName }.filter { name ->
                (outcomes[name.lowercase()] as? RepositoryOutcome.Scanned)?.result?.skills?.isNotEmpty() == true
            }
            outcome.copy(withSkills = withSkills)
        }
        val failures = order.mapNotNull { entry ->
            if (entry.owner) {
                (searched.getValue(entry.key) as? OwnerOutcome.Failed)?.let { ScanFailure(it.label, it.error) }
            } else {
                (outcomes.getValue(entry.key) as? RepositoryOutcome.Failed)?.let { ScanFailure(it.label, it.error) }
            }
        }
        return MultiScan(outcomes.values.toList(), searched.values.toList(), failures)
    }

    private class Task(val url: String, val listed: RepositoryMetadata?, val from: String?)

    /** A key of `owners` or of `tasks`, in URL order. */
    private class Entry(val key: String, val owner: Boolean)

    private fun discoverOne(url: String, login: String, discover: (String) -> OwnerDiscovery): OwnerOutcome = try {
        OwnerOutcome.Searched(url, discover(login), withSkills = emptyList())
    } catch (e: SkillAtlasException) {
        OwnerOutcome.Failed(url, login, e)
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        OwnerOutcome.Failed(url, login, UnexpectedFailureException(e))
    }

    /** Scans every task, [parallelism] at a time; the result keeps the tasks' order and keys. */
    private fun scanTasks(tasks: Map<String, Task>, scan: (String, RepositoryMetadata?) -> ScanResult): Map<String, RepositoryOutcome> {
        if (tasks.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(parallelism, tasks.size))
        try {
            val futures = tasks.mapValues { (_, task) -> pool.submit(Callable { scanOne(task, scan) }) }
            return futures.mapValues { (_, future) ->
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

    private fun scanOne(task: Task, scan: (String, RepositoryMetadata?) -> ScanResult): RepositoryOutcome {
        val label = task.listed?.fullName ?: runCatching { GitHubUrl.parse(task.url).toString() }.getOrElse { task.url.trim() }
        return try {
            RepositoryOutcome.Scanned(task.url, scan(task.url, task.listed), task.from)
        } catch (e: SkillAtlasException) {
            RepositoryOutcome.Failed(task.url, label, e, task.from)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            RepositoryOutcome.Failed(task.url, label, UnexpectedFailureException(e), task.from)
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

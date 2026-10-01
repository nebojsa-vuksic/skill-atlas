package skillatlas

/** What `scan` shows once the scan is done: the skill list, or one skill (spec sections 5, 5.7 and 5.10). */
sealed interface Presentation {
    /** The repositories that were scanned successfully, each of which goes into the scan log. */
    val results: List<ScanResult>

    /** All skills, or with a [query] only those that match it (spec section 5.7). */
    data class SkillList(val result: ScanResult, val query: String? = null) : Presentation {
        override val results: List<ScanResult> get() = listOf(result)

        val words: List<String>? = query?.let(SkillFilter::words)
        val skills: List<Skill> = when {
            query == null -> result.skills
            !SkillFilter.parse(query).matchesRepository(result.repository.fullName) -> emptyList()
            else -> SkillFilter.filter(result.skills, words.orEmpty())
        }
    }

    /** One skill with its similar skills and file text, like the web view's right pane. */
    data class SkillDetail(
        val result: ScanResult,
        val skill: Skill,
        val similar: List<SimilarSkill>,
        /** Every scanned repository; more than [result] when several were scanned. */
        override val results: List<ScanResult> = listOf(result),
    ) : Presentation {
        val content: String? get() = result.contents[skill.path]
        val githubUrl: String get() = githubFileUrl(result, skill.path)
    }

    /**
     * Several repositories: one list per reported repository, in URL order (spec section 5.10), then
     * the `Searched` line of each owner (spec section 5.11).
     */
    data class MultiList(val scan: MultiScan, val query: String? = null) : Presentation {
        constructor(outcomes: List<RepositoryOutcome>, query: String? = null) : this(MultiScan(outcomes), query)

        val lists: List<SkillList> = scan.reported.filterIsInstance<RepositoryOutcome.Scanned>().map { SkillList(it.result, query) }
        override val results: List<ScanResult> get() = lists.map { it.result }

        val owners: List<OwnerOutcome.Searched> get() = scan.owners.filterIsInstance<OwnerOutcome.Searched>()

        /** e.g. `Scanned 2 repositories: 5 of 44 skills match "test", 1 failed`. */
        val summary: String
            get() {
                val total = lists.sumOf { it.result.skills.size }
                val counts = if (query == null) {
                    "$total ${if (total == 1) "skill" else "skills"}"
                } else {
                    "${lists.sumOf { it.skills.size }} of $total skills match \"${sanitize(query)}\""
                }
                val failed = scan.failures.size
                val repositories = if (lists.size == 1) "repository" else "repositories"
                return "Scanned ${lists.size} $repositories: $counts" + if (failed > 0) ", $failed failed" else ""
            }
    }

    companion object {
        /**
         * `--skill` across several repositories (spec section 5.10): `owner/repo:path`, then a
         * directory path, then a name. Similar skills come from all repositories, as ids.
         */
        fun detail(results: List<ScanResult>, selector: String): SkillDetail {
            if (results.size == 1) return detail(results.single(), selector)
            val value = selector.trim().removeSuffix("/")
            val similar by lazy { crossRepositorySimilarity(results) }
            fun found(result: ScanResult, skill: Skill) = SkillDetail(
                result, skill, similar.getValue(skillId(result.repository.fullName, skill.path)), results,
            )

            val qualified = results.firstNotNullOfOrNull { result ->
                val prefix = "${result.repository.fullName}:"
                if (value.startsWith(prefix, ignoreCase = true)) result to value.substring(prefix.length) else null
            }
            if (qualified != null) {
                val (result, path) = qualified
                val skill = result.skills.firstOrNull { path == it.path || path in it.alsoAt }
                    ?: throw SkillNotFoundException(selector, result.repository.fullName)
                return found(result, skill)
            }

            fun matches(test: (Skill) -> Boolean) =
                results.flatMap { result -> result.skills.filter(test).map { result to it } }
            val byPath = matches { value == it.path || value in it.alsoAt }
            val candidates = byPath.ifEmpty { matches { it.name.equals(value, ignoreCase = true) } }
            return when (candidates.size) {
                0 -> throw SkillNotFoundException(selector, results.joinToString(", ") { it.repository.fullName })
                1 -> candidates.single().let { (result, skill) -> found(result, skill) }
                else -> throw AmbiguousSkillException(selector, candidates.map { (result, skill) -> skillId(result.repository.fullName, skill.path) })
            }
        }

        /** The skill named by `--skill`: a directory path (copies included) first, then a name, ignoring case. */
        fun detail(result: ScanResult, selector: String): SkillDetail {
            val value = selector.trim().removeSuffix("/")
            val skill = result.skills.firstOrNull { value == it.path || value in it.alsoAt }
                ?: result.skills.filter { it.name.equals(value, ignoreCase = true) }.let { named ->
                    when (named.size) {
                        0 -> throw SkillNotFoundException(selector, result.repository.fullName)
                        1 -> named.single()
                        else -> throw AmbiguousSkillException(selector, named.map { it.path })
                    }
                }
            val similar = SkillSimilarity.compute(result.skills, result.contents).getValue(skill.path)
            return SkillDetail(result, skill, similar)
        }
    }
}

/** `https://github.com/<repository>/blob/<commit>/<path>/SKILL.md` for a skill directory. */
fun githubFileUrl(result: ScanResult, skillPath: String): String {
    val directory = if (skillPath == ".") "" else "$skillPath/"
    return "https://github.com/${result.repository.fullName}/blob/${result.commit}/${directory}SKILL.md"
}

/** The 10-cell bar for a similarity score in percent, e.g. `████░░░░░░` for 42 (spec section 5.7). */
fun similarityBar(score: Int): String {
    val filled = Math.round(score / 10.0).toInt().coerceIn(0, 10)
    return "█".repeat(filled) + "░".repeat(10 - filled)
}

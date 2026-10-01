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
            else -> SkillFilter.filter(result.skills, SkillFilter.parse(query))
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

    /** What `star` or `unstar` did to [skill]: [starred] is its new state, and [changed] is false when it already had it (spec section 5.11). */
    data class StarChanged(val result: ScanResult, val skill: Skill, val starred: Boolean, val changed: Boolean) : Presentation {
        override val results: List<ScanResult> get() = listOf(result)

        val id: String get() = skillId(result.repository.fullName, skill.path)

        /** The text before the skill's name, e.g. `Starred `. */
        val before: String get() = if (!changed) "" else if (starred) "Starred " else "Unstarred "

        /** The text between the name and the id, e.g. ` is already starred (`. */
        val after: String get() = when {
            changed -> " ("
            starred -> " is already starred ("
            else -> " isn't starred ("
        }

        /** e.g. `Starred pdf (acme/skills:skills/pdf).` */
        val message: String get() = "$before${sanitize(skill.name)}$after${sanitize(id)})."
    }

    /** `stars`: every starred skill, in file order (spec section 5.11). Nothing was scanned. */
    data class StarList(val stars: List<Star>) : Presentation {
        override val results: List<ScanResult> get() = emptyList()

        /** e.g. `2 starred skills`, or `No starred skills yet.` */
        val heading: String
            get() = if (stars.isEmpty()) "No starred skills yet." else "${stars.size} starred ${if (stars.size == 1) "skill" else "skills"}"
    }

    /** Several repositories: one list per scanned repository, in URL order (spec section 5.10). */
    data class MultiList(val outcomes: List<RepositoryOutcome>, val query: String? = null) : Presentation {
        val lists: List<SkillList> = outcomes.filterIsInstance<RepositoryOutcome.Scanned>().map { SkillList(it.result, query) }
        override val results: List<ScanResult> get() = lists.map { it.result }

        /** e.g. `Scanned 2 repositories: 5 of 44 skills match "test", 1 failed`. */
        val summary: String
            get() {
                val total = lists.sumOf { it.result.skills.size }
                val counts = if (query == null) {
                    "$total ${if (total == 1) "skill" else "skills"}"
                } else {
                    "${lists.sumOf { it.skills.size }} of $total skills match \"${sanitize(query)}\""
                }
                val failed = outcomes.count { it is RepositoryOutcome.Failed }
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

        /** The skill named by `--skill`, with its similar skills and content. */
        fun detail(result: ScanResult, selector: String): SkillDetail {
            val skill = findSkill(result, selector)
            val similar = SkillSimilarity.compute(result.skills, result.contents).getValue(skill.path)
            return SkillDetail(result, skill, similar)
        }

        /** The skill named by `--skill`, `star` or `unstar`: a directory path (copies included) first, then a name, ignoring case. */
        fun findSkill(result: ScanResult, selector: String): Skill {
            val value = selector.trim().removeSuffix("/")
            return result.skills.firstOrNull { value == it.path || value in it.alsoAt }
                ?: result.skills.filter { it.name.equals(value, ignoreCase = true) }.let { named ->
                    when (named.size) {
                        0 -> throw SkillNotFoundException(selector, result.repository.fullName)
                        1 -> named.single()
                        else -> throw AmbiguousSkillException(selector, named.map { it.path })
                    }
                }
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

package skillatlas

/** What `scan` shows once the scan is done: the skill list, or one skill (spec sections 5 and 5.7). */
sealed interface Presentation {
    val result: ScanResult

    /** All skills, or with a [query] only those that match it (spec section 5.7). */
    data class SkillList(override val result: ScanResult, val query: String? = null) : Presentation {
        val words: List<String>? = query?.let(SkillFilter::words)
        val skills: List<Skill> = if (words == null) result.skills else SkillFilter.filter(result.skills, words)
    }

    /** One skill with its similar skills and file text, like the web view's right pane. */
    data class SkillDetail(
        override val result: ScanResult,
        val skill: Skill,
        val similar: List<SimilarSkill>,
    ) : Presentation {
        val content: String? get() = result.contents[skill.path]
        val githubUrl: String get() = githubFileUrl(result, skill.path)
    }

    companion object {
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

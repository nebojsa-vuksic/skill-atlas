package skillatlas

data class ScanResult(
    val repository: RepositoryMetadata,
    val branch: String,
    val commit: String,
    val skills: List<Skill>,
    /** Skill files that are not skills of this repository, such as test fixtures (spec section 4.4). */
    val ignored: List<IgnoredSkill> = emptyList(),
    /** The exact text of each skill's file, keyed by [Skill.path] (spec section 5.4). */
    val contents: Map<String, String> = emptyMap(),
)

/** Renders the plain text report (spec sections 5.2 and 5.7). */
object TextReport {
    fun render(result: ScanResult): String = render(Presentation.SkillList(result))

    fun render(presentation: Presentation): String = buildString {
        when (presentation) {
            is Presentation.SkillList -> {
                header(presentation.result)
                skillList(presentation)
            }
            is Presentation.SkillDetail -> {
                header(presentation.result)
                skillDetail(presentation)
            }
            is Presentation.MultiList -> {
                presentation.lists.forEachIndexed { i, list ->
                    if (i > 0) {
                        appendLine()
                        appendLine(REPOSITORY_SEPARATOR)
                        appendLine()
                    }
                    header(list.result)
                    skillList(list)
                }
                if (presentation.lists.isNotEmpty()) appendLine()
                for (owner in presentation.owners) appendLine(owner.line)
                appendLine(presentation.summary)
            }
            is Presentation.StarChanged -> appendLine(presentation.message)
            is Presentation.StarList -> starList(presentation)
        }
    }

    private fun StringBuilder.starList(list: Presentation.StarList) {
        if (list.stars.isEmpty()) {
            appendLine(list.heading)
            return
        }
        appendLine("${list.heading}:")
        appendLine()
        val width = list.stars.maxOf { sanitize(it.name).length }
        for (star in list.stars) appendLine("  ${sanitize(star.name).padEnd(width)}  ${sanitize(star.id)}")
    }

    private fun StringBuilder.header(result: ScanResult) {
        val description = result.repository.description?.let(::sanitize)?.ifBlank { null } ?: "(none)"
        appendLine("Repository:  ${result.repository.fullName}")
        appendLine("Description: $description")
        appendLine("Commit:      ${result.commit} (${sanitize(result.branch)})")
        appendLine()
    }

    private fun StringBuilder.skillList(list: Presentation.SkillList) {
        val result = list.result
        val words = list.words
        val total = result.skills.size
        val skills = list.skills
        when {
            total == 0 -> appendLine("No skills found.")
            words == null -> appendLine("Found $total ${skillsWord(total)}:")
            skills.isEmpty() -> appendLine("Found $total ${skillsWord(total)}, none match \"${sanitize(list.query.orEmpty())}\".")
            else -> appendLine("Found $total ${skillsWord(total)}, ${skills.size} match \"${sanitize(list.query.orEmpty())}\":")
        }
        for (skill in skills) {
            appendLine()
            append("  ").append(sanitize(skill.name))
            tags(skill)
            appendLine()
            val line = if (words == null) shortenDescription(skill.description) else SkillFilter.descriptionLine(skill, words)
            appendLine("    ${line?.ifEmpty { null } ?: "(no description)"}")
            appendLine("    ${sanitize(skill.path)}")
            for (copy in skill.alsoAt) appendLine("    also in ${sanitize(copy)}")
        }

        if (result.ignored.isNotEmpty()) {
            appendLine()
            appendLine("${ignoredHeading(result.ignored.size)}:")
            for (ignored in result.ignored) appendLine("  ${sanitize(ignored.path)}")
        }
    }

    private fun StringBuilder.skillDetail(detail: Presentation.SkillDetail) {
        val skill = detail.skill
        append("Skill:       ").append(sanitize(skill.name))
        tags(skill)
        appendLine()
        appendLine("Path:        ${sanitize(skill.path)}")
        skill.alsoAt.forEachIndexed { i, copy -> appendLine("${if (i == 0) "Also in:     " else "             "}${sanitize(copy)}") }
        appendLine("GitHub:      ${detail.githubUrl}")
        appendLine()

        appendLine("Description:")
        indented(sanitize(skill.description).trim().ifEmpty { "(no description)" })
        appendLine()

        appendLine("Similar skills:")
        if (detail.similar.isEmpty()) appendLine("  No similar skills found.")
        val width = detail.similar.maxOfOrNull { it.name.length } ?: 0
        for (similar in detail.similar) {
            appendLine("  ${sanitize(similar.name).padEnd(width)}  ${similarityBar(similar.score)} ${"%3d %%".format(similar.score)}  ${sanitize(similar.path)}")
        }
        appendLine()

        appendLine("SKILL.md:")
        indented(detail.content?.let(::sanitize)?.trimEnd('\n') ?: "(content not available)")
    }

    private fun StringBuilder.tags(skill: Skill) {
        if (skill.starred) append("  [$STARRED_LABEL]")
        if (skill.shipped) append("  [$SHIPPED_LABEL]")
        if (skill.warnings.isNotEmpty()) append("  [warning: ${skill.warnings.joinToString(", ")}]")
    }

    private fun StringBuilder.indented(text: String) {
        for (line in text.lines()) appendLine(if (line.isEmpty()) "" else "  $line")
    }

    private fun skillsWord(count: Int) = if (count == 1) "skill" else "skills"
}

const val MAX_DESCRIPTION_LENGTH = 100

const val SHIPPED_LABEL = "shipped in product"

const val STARRED_LABEL = "starred"

/** Between repositories when `scan` is given several URLs (spec section 5.10). */
val REPOSITORY_SEPARATOR = "─".repeat(80)

/** e.g. "Ignored 2 test fixtures (not skills)" (spec section 5.2). */
fun ignoredHeading(count: Int) = "Ignored $count test ${if (count == 1) "fixture" else "fixtures"} (not skills)"

private val WHITESPACE = Regex("\\s+")

/**
 * Collapses [description] onto one line and, if it is longer than [maxLength], cuts it at
 * the last word boundary and appends `…` (spec section 5.3).
 */
fun shortenDescription(description: String, maxLength: Int = MAX_DESCRIPTION_LENGTH): String {
    val text = sanitize(description).replace(WHITESPACE, " ").trim()
    if (text.length <= maxLength) return text

    var cut = text.substring(0, maxLength)
    val lastSpace = cut.lastIndexOf(' ')
    if (text[maxLength] != ' ' && lastSpace > 0) cut = cut.substring(0, lastSpace)
    if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trimEnd(' ', ',', ';', ':', '.', '-', '–', '—') + "…"
}

/** Drops control characters so text from a scanned repository cannot inject terminal escape sequences. */
fun sanitize(text: String) = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }

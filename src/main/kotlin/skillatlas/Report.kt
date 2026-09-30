package skillatlas

data class ScanResult(
    val repository: RepositoryMetadata,
    val branch: String,
    val commit: String,
    val skills: List<Skill>,
)

/** Renders the plain text report (spec section 5.2). */
object TextReport {
    fun render(result: ScanResult): String = buildString {
        val description = result.repository.description?.let(::sanitize)?.ifBlank { null } ?: "(none)"
        appendLine("Repository:  ${result.repository.fullName}")
        appendLine("Description: $description")
        appendLine("Commit:      ${result.commit} (${sanitize(result.branch)})")
        appendLine()

        val skills = result.skills
        if (skills.isEmpty()) {
            appendLine("No skills found.")
            return@buildString
        }
        appendLine("Found ${skills.size} ${if (skills.size == 1) "skill" else "skills"}:")
        for (skill in skills) {
            appendLine()
            append("  ").append(sanitize(skill.name))
            if (skill.warnings.isNotEmpty()) append("  [warning: ${skill.warnings.joinToString(", ")}]")
            appendLine()
            appendLine("    ${shortenDescription(skill.description).ifEmpty { "(no description)" }}")
            appendLine("    ${sanitize(skill.path)}")
        }
    }
}

const val MAX_DESCRIPTION_LENGTH = 100

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

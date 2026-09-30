package skillatlas

data class ScanResult(
    val repository: RepositoryMetadata,
    val branch: String,
    val commit: String,
    val skills: List<Skill>,
)

/** Renders the human-readable report (spec section 5.1). */
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
            for (line in sanitize(skill.description).ifEmpty { "(no description)" }.lines()) {
                if (line.isEmpty()) appendLine() else appendLine("    $line")
            }
            appendLine("    ${sanitize(skill.path)}")
        }
    }

    /** Drops control characters so text from a scanned repository cannot inject terminal escape sequences. */
    private fun sanitize(text: String) = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }
}

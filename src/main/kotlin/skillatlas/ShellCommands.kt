package skillatlas

/** Whether a shell command takes an argument (spec section 5.9). */
enum class ArgumentKind { NONE, OPTIONAL, REQUIRED }

/** One slash command of the shell. [usage] is shown after the name, e.g. `<url>`. */
data class ShellCommand(
    val name: String,
    val usage: String,
    val description: String,
    val argument: ArgumentKind,
    val needsRepository: Boolean,
) {
    val label: String get() = if (usage.isEmpty()) "/$name" else "/$name $usage"
}

/** The shell's commands, in the order the palette and `/help` list them (spec section 5.9). */
object ShellCommands {
    val ALL = listOf(
        ShellCommand("scan", "<url>", "Scan a GitHub repository and make it the current one", ArgumentKind.REQUIRED, needsRepository = false),
        ShellCommand("filter", "<words>", "List the skills whose name or description has every word", ArgumentKind.OPTIONAL, needsRepository = true),
        ShellCommand("skill", "<name-or-path>", "Show one skill: description, paths, similar skills, SKILL.md", ArgumentKind.REQUIRED, needsRepository = true),
        ShellCommand("similar", "<name-or-path>", "Show the skills most similar to one skill", ArgumentKind.REQUIRED, needsRepository = true),
        ShellCommand("browse", "", "Browse the current repository full-screen; q returns here", ArgumentKind.NONE, needsRepository = true),
        ShellCommand("repo", "", "Show the current repository", ArgumentKind.NONE, needsRepository = true),
        ShellCommand("serve", "[port]", "Start the web view in the background; /serve stop stops it", ArgumentKind.OPTIONAL, needsRepository = false),
        ShellCommand("log", "", "Show the last 10 scans from the scan log", ArgumentKind.NONE, needsRepository = false),
        ShellCommand("help", "", "List the commands and keys", ArgumentKind.NONE, needsRepository = false),
        ShellCommand("quit", "", "Leave the shell", ArgumentKind.NONE, needsRepository = false),
    )

    fun find(name: String): ShellCommand? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The commands whose argument is a skill, which the palette completes with skill names. */
    val SKILL_ARGUMENT = setOf("skill", "similar")
}

/** A candidate that matched the palette's query, and where in [text] it matched. */
data class PaletteMatch(val index: Int, val text: String, val range: IntRange?)

/**
 * Ranks candidates against the palette's query (spec section 5.9): exact matches, then
 * prefixes, then substrings, ignoring case; the candidates' order is kept within each group.
 */
object PaletteMatcher {
    fun rank(query: String, candidates: List<String>): List<PaletteMatch> {
        val needle = query.lowercase()
        if (needle.isEmpty()) return candidates.mapIndexed { i, text -> PaletteMatch(i, text, null) }
        return candidates.mapIndexedNotNull { i, text ->
            val at = text.lowercase().indexOf(needle)
            if (at < 0) return@mapIndexedNotNull null
            val group = when {
                text.length == needle.length -> 0
                at == 0 -> 1
                else -> 2
            }
            group to PaletteMatch(i, text, at until at + needle.length)
        }.sortedBy { it.first }.map { it.second }
    }
}

/** One row of the palette: what it shows, and what choosing it does. */
sealed interface PaletteItem {
    /** The text the row is matched on and highlighted in. */
    val match: PaletteMatch
    val description: String

    /** A command; [ShellCommand.label] is shown, and the match is inside its name. */
    data class Command(val command: ShellCommand, override val match: PaletteMatch) : PaletteItem {
        override val description: String get() = command.description

        /** What Tab puts into the input. */
        val completion: String get() = if (command.argument == ArgumentKind.NONE) "/${command.name}" else "/${command.name} "
    }

    /** A skill argument for `/skill` or `/similar`: a name, or a path when the name is shared. */
    data class SkillArgument(val command: String, override val match: PaletteMatch, override val description: String) : PaletteItem {
        val completion: String get() = "/$command ${match.text}"
    }
}

/** What the palette offers for the current input (spec section 5.9). */
object ShellCompletion {
    private val SKILL_INPUT = Regex("^/(\\S+) (.*)$")

    /** [skills] are the current repository's, or null when there is none. */
    fun suggest(input: String, skills: List<Skill>?): List<PaletteItem> {
        if (!input.startsWith("/")) return emptyList()
        if (' ' !in input) {
            val commands = ShellCommands.ALL
            return PaletteMatcher.rank(input.drop(1), commands.map { it.name })
                .map { PaletteItem.Command(commands[it.index], it) }
        }
        val parts = SKILL_INPUT.matchEntire(input) ?: return emptyList()
        val command = ShellCommands.find(parts.groupValues[1])?.name ?: return emptyList()
        if (command !in ShellCommands.SKILL_ARGUMENT || skills == null) return emptyList()
        val arguments = skillArguments(skills)
        return PaletteMatcher.rank(parts.groupValues[2].trimStart(), arguments.map { it.first })
            .map { PaletteItem.SkillArgument(command, it, arguments[it.index].second) }
    }

    /** Each skill's name with its shortened description, or its path when several skills share the name. */
    private fun skillArguments(skills: List<Skill>): List<Pair<String, String>> {
        val counts = skills.groupingBy { it.name.lowercase() }.eachCount()
        return skills.map { skill ->
            val value = if (counts.getValue(skill.name.lowercase()) > 1) skill.path else skill.name
            sanitize(value) to shortenDescription(skill.description)
        }
    }
}

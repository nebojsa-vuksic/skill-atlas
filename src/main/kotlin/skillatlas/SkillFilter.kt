package skillatlas

/**
 * The filter rules of spec section 5.5, shared by `scan --filter` and `browse`. They mirror
 * the web view's `app.js` exactly, so every view agrees on what matches.
 */
object SkillFilter {
    private const val SNIPPET_CONTEXT = 40
    private val WHITESPACE = Regex("\\s+")

    /** The query's words, lowercased; an empty list matches every skill. */
    fun words(query: String): List<String> = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    /** True when every word is in the skill's name or full description, ignoring case. */
    fun matches(skill: Skill, words: List<String>): Boolean {
        val name = skill.name.lowercase()
        val description = skill.description.lowercase()
        return words.all { it in name || it in description }
    }

    fun filter(skills: List<Skill>, words: List<String>): List<Skill> = skills.filter { matches(it, words) }

    /** Every range of [text] where one of [words] occurs, merged and sorted. */
    fun matchRanges(text: String, words: List<String>): List<IntRange> {
        val lower = text.lowercase()
        val ranges = words.flatMap { word ->
            generateSequence(lower.indexOf(word).takeIf { it >= 0 }) { at -> lower.indexOf(word, at + 1).takeIf { it >= 0 } }
                .map { at -> at until at + word.length }
                .toList()
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * The description line for a listed skill: its shortened description, or a snippet
     * around the first match when a word occurs only past what the shortened description
     * shows. Null when the skill has no description.
     */
    fun descriptionLine(skill: Skill, words: List<String>, maxLength: Int = MAX_DESCRIPTION_LENGTH): String? {
        val short = shortenDescription(skill.description, maxLength)
        if (short.isEmpty()) return null
        val shown = short.removeSuffix("…").lowercase()
        val full = sanitize(skill.description).replace(WHITESPACE, " ").trim()
        val lower = full.lowercase()
        var first = -1
        var length = 0
        for (word in words) {
            val at = lower.indexOf(word)
            if (at < 0 || word in shown) continue
            if (first < 0 || at < first) {
                first = at
                length = word.length
            }
        }
        return if (first < 0) short else snippet(full, first, first + length)
    }

    /** Up to [SNIPPET_CONTEXT] characters on each side of `text[start, end)`, cut at word boundaries. */
    fun snippet(text: String, start: Int, end: Int): String {
        var from = maxOf(0, start - SNIPPET_CONTEXT)
        if (from > 0 && text[from - 1] != ' ') {
            val space = text.indexOf(' ', from)
            from = if (space in 0 until start) space + 1 else start
        }
        var to = minOf(text.length, end + SNIPPET_CONTEXT)
        if (to < text.length && text[to] != ' ') {
            val space = text.lastIndexOf(' ', to)
            to = if (space >= end) space else end
        }
        return (if (from > 0) "…" else "") + text.substring(from, to).trim() + (if (to < text.length) "…" else "")
    }
}

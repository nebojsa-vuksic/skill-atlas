package skillatlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which part of `browse` receives the keys (spec section 5.8). */
enum class BrowseFocus { LIST, FILTER, SIMILAR }

/**
 * Everything `browse` shows and how each key changes it (spec section 5.8). Plain Kotlin on
 * snapshot state, so the screen recomposes on change and tests can drive it without a terminal.
 */
class BrowseState(val result: ScanResult, private val similarByPath: Map<String, List<SimilarSkill>>) {
    var query by mutableStateOf("")
        private set
    var focus by mutableStateOf(BrowseFocus.LIST)
        private set

    /** The selected skill's path. It is kept while a filter hides everything, so clearing brings it back. */
    var selectedPath by mutableStateOf(result.skills.firstOrNull()?.path)
        private set
    var similarCursor by mutableIntStateOf(0)
        private set

    /** How many lines the right pane is scrolled down. */
    var scroll by mutableIntStateOf(0)
        private set
    var quit by mutableStateOf(false)
        private set

    val words: List<String> get() = SkillFilter.words(query)
    val visible: List<Skill>
        get() = if (SkillFilter.parse(query).matchesRepository(result.repository.fullName)) {
            SkillFilter.filter(result.skills, words)
        } else {
            emptyList()
        }
    val selected: Skill? get() = visible.firstOrNull { it.path == selectedPath }
    val similar: List<SimilarSkill> get() = selected?.let { similarByPath[it.path] }.orEmpty()

    /**
     * Handles one key. [page] is the right pane's height and [maxScroll] how far it can
     * scroll, both from the current screen. Returns false for keys `browse` doesn't use.
     */
    fun onKey(key: String, ctrl: Boolean = false, alt: Boolean = false, page: Int = 1, maxScroll: Int = 0): Boolean {
        if (ctrl && key.equals("c", ignoreCase = true)) {
            quit = true
            return true
        }
        return when (focus) {
            BrowseFocus.FILTER -> onFilterKey(key, ctrl || alt)
            BrowseFocus.LIST -> onListKey(key, page, maxScroll)
            BrowseFocus.SIMILAR -> onSimilarKey(key)
        }
    }

    private fun onFilterKey(key: String, modified: Boolean): Boolean {
        when {
            key == "Enter" || key == "ArrowDown" -> focus = BrowseFocus.LIST
            key == "Escape" -> {
                changeQuery("")
                focus = BrowseFocus.LIST
            }
            key == "Backspace" -> changeQuery(query.dropLast(1))
            key.length == 1 && !modified -> changeQuery(query + key)
            else -> return false
        }
        return true
    }

    private fun onListKey(key: String, page: Int, maxScroll: Int): Boolean {
        val skills = visible
        val index = skills.indexOfFirst { it.path == selectedPath }
        when (key) {
            "ArrowDown" -> skills.getOrNull(if (index < 0) 0 else minOf(index + 1, skills.lastIndex))?.let { select(it.path) }
            "ArrowUp" -> skills.getOrNull(if (index < 0) 0 else maxOf(index - 1, 0))?.let { select(it.path) }
            "Home" -> skills.firstOrNull()?.let { select(it.path) }
            "End" -> skills.lastOrNull()?.let { select(it.path) }
            "/" -> focus = BrowseFocus.FILTER
            "Tab" -> if (similar.isNotEmpty()) {
                similarCursor = 0
                focus = BrowseFocus.SIMILAR
            }
            "Escape" -> changeQuery("")
            "PageDown" -> scroll = minOf(scroll + maxOf(page - 1, 1), maxOf(maxScroll, 0))
            "PageUp" -> scroll = maxOf(scroll - maxOf(page - 1, 1), 0)
            "q" -> quit = true
            else -> return false
        }
        return true
    }

    private fun onSimilarKey(key: String): Boolean {
        when (key) {
            "ArrowDown" -> similarCursor = minOf(similarCursor + 1, similar.lastIndex)
            "ArrowUp" -> similarCursor = maxOf(similarCursor - 1, 0)
            "Enter" -> {
                val target = similar.getOrNull(similarCursor)?.path ?: return true
                if (visible.none { it.path == target }) changeQuery("")
                select(target)
                focus = BrowseFocus.LIST
            }
            "Tab", "Escape" -> focus = BrowseFocus.LIST
            else -> return false
        }
        return true
    }

    /** Changes the filter, keeping the selection if it is still visible and otherwise taking the first visible skill. */
    private fun changeQuery(value: String) {
        query = value
        val skills = visible
        if (skills.isNotEmpty() && skills.none { it.path == selectedPath }) select(skills.first().path)
    }

    private fun select(path: String) {
        if (path != selectedPath) scroll = 0
        selectedPath = path
        similarCursor = 0
    }
}

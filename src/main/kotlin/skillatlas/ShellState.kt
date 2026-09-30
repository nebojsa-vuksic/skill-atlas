package skillatlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The inputs run in this shell session, walked with ↑ and ↓ (spec section 5.9). */
class InputHistory {
    private val entries = mutableListOf<String>()

    /** Where ↑/↓ are in [entries]; `entries.size` means "not walking, at the draft". */
    private var position = 0
    private var draft = ""

    val size: Int get() = entries.size

    /** Records a run input, skipping blanks and a repeat of the newest entry, and stops walking. */
    fun add(input: String) {
        if (input.isNotBlank() && entries.lastOrNull() != input) entries.add(input)
        position = entries.size
        draft = ""
    }

    /** The entry before the current one, or null at the oldest. [current] is saved as the draft when walking starts. */
    fun previous(current: String): String? {
        if (position == 0) return null
        if (position == entries.size) draft = current
        position--
        return entries[position]
    }

    /** The entry after the current one, the saved draft past the newest, or null when not walking. */
    fun next(): String? {
        if (position >= entries.size) return null
        position++
        return if (position == entries.size) draft else entries[position]
    }

    /** Stops walking, e.g. after the input was edited or cleared. */
    fun reset() {
        position = entries.size
    }
}

/** What a key at the prompt asks the shell to do, beyond changing the input. */
sealed interface ShellAction {
    data class Run(val line: String) : ShellAction
    data object Quit : ShellAction
}

/**
 * The prompt, the palette, and how each key changes them (spec section 5.9). Plain Kotlin on
 * snapshot state, so the screen recomposes on change and tests can drive it without a terminal.
 * [skills] gives the current repository's skills, or null when there is none.
 */
class ShellState(private val skills: () -> List<Skill>? = { null }) {
    var input by mutableStateOf("")
        private set

    /** The cursor's index in [input], from 0 to `input.length`. */
    var cursor by mutableIntStateOf(0)
        private set

    /** The selected palette row. */
    var selection by mutableIntStateOf(0)
        private set

    /** True after Esc, until the input is edited again. */
    var dismissed by mutableStateOf(false)
        private set

    val history = InputHistory()

    /** The palette rows for the current input; empty when the palette is closed. */
    val palette: List<PaletteItem> get() = if (dismissed) emptyList() else ShellCompletion.suggest(input, skills())

    /** Handles one key and returns what the shell should do, or null when only the prompt changed. */
    fun onKey(key: String, ctrl: Boolean = false, alt: Boolean = false): ShellAction? {
        if (ctrl) return onControlKey(key.lowercase())
        val items = palette
        when (key) {
            "Enter" -> return if (items.isNotEmpty()) choose(items[selection.coerceIn(items.indices)]) else submit(input)
            "Tab" -> items.getOrNull(selection)?.let { setInput(completionOf(it)) }
            "Escape" -> dismissed = true
            "ArrowUp" -> if (items.isNotEmpty()) selection = maxOf(selection - 1, 0) else history.previous(input)?.let(::recall)
            "ArrowDown" -> if (items.isNotEmpty()) selection = minOf(selection + 1, items.lastIndex) else history.next()?.let(::recall)
            "ArrowLeft" -> cursor = maxOf(cursor - 1, 0)
            "ArrowRight" -> cursor = minOf(cursor + 1, input.length)
            "Home" -> cursor = 0
            "End" -> cursor = input.length
            "Backspace" -> if (cursor > 0) edit(input.removeRange(cursor - 1, cursor), cursor - 1)
            "Delete" -> if (cursor < input.length) edit(input.removeRange(cursor, cursor + 1), cursor)
            // One character, which may be a surrogate pair outside the BMP.
            else -> if (key.codePointCount(0, key.length) == 1 && !alt) edit(input.substring(0, cursor) + key + input.substring(cursor), cursor + key.length)
        }
        return null
    }

    private fun onControlKey(key: String): ShellAction? {
        when (key) {
            "c" -> if (input.isEmpty()) return ShellAction.Quit else edit("", 0)
            "d" -> if (input.isEmpty()) return ShellAction.Quit
            "a" -> cursor = 0
            "e" -> cursor = input.length
            "u" -> edit(input.substring(cursor), 0)
        }
        return null
    }

    /** Enter on a palette row: run it, or complete it when the command still needs its argument. */
    private fun choose(item: PaletteItem): ShellAction? = when (item) {
        is PaletteItem.Command ->
            if (item.command.argument == ArgumentKind.REQUIRED) {
                setInput(item.completion)
                null
            } else {
                submit(item.completion.trimEnd())
            }
        is PaletteItem.SkillArgument -> submit(item.completion)
    }

    private fun completionOf(item: PaletteItem) = when (item) {
        is PaletteItem.Command -> item.completion
        is PaletteItem.SkillArgument -> item.completion
    }

    private fun submit(line: String): ShellAction? {
        val trimmed = line.trim()
        setInput("")
        if (trimmed.isEmpty()) return null
        history.add(trimmed)
        return ShellAction.Run(trimmed)
    }

    /** A typed change: the palette reopens and selects its first row, and history walking stops. */
    private fun edit(value: String, at: Int) {
        setInput(value, at)
        history.reset()
    }

    /** A history entry; the palette stays closed, so ↑ and ↓ keep walking the history. */
    private fun recall(value: String) {
        setInput(value)
        dismissed = true
    }

    private fun setInput(value: String, at: Int = value.length) {
        input = value
        cursor = at.coerceIn(0, value.length)
        selection = 0
        dismissed = false
    }
}

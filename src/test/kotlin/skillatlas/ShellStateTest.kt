package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The input history and every key at the shell's prompt (spec section 5.9). */
class ShellStateTest {
    private val skills = listOf(
        Skill("mps-tests", "Use when writing tests.", "skills/mps-tests"),
        Skill("mps-run", "Run configurations.", "skills/mps-run"),
    )

    private fun ShellState.type(text: String) = text.forEach { onKey(it.toString()) }

    private fun ShellState.run(text: String): ShellAction? {
        type(text)
        return onKey("Escape").let { onKey("Enter") }
    }

    private val ShellState.paletteNames get() = palette.map { it.match.text }

    // ---- History ----

    @Test
    fun `history walks back and forward and restores the draft`() {
        val history = InputHistory()
        history.add("/scan a")
        history.add("/repo")

        assertEquals("/repo", history.previous("dra"))
        assertEquals("/scan a", history.previous("ignored"))
        assertNull(history.previous("ignored"))
        assertEquals("/repo", history.next())
        assertEquals("dra", history.next())
        assertNull(history.next())
    }

    @Test
    fun `history skips blanks and repeats of the newest entry`() {
        val history = InputHistory()
        history.add("/repo")
        history.add("/repo")
        history.add("  ")
        history.add("/log")
        history.add("/repo")

        assertEquals(3, history.size)
        assertEquals("/repo", history.previous(""))
        assertEquals("/log", history.previous(""))
        assertEquals("/repo", history.previous(""))
    }

    @Test
    fun `adding or resetting stops walking`() {
        val history = InputHistory()
        history.add("/a")
        history.add("/b")
        history.previous("")
        history.reset()

        assertNull(history.next())
        assertEquals("/b", history.previous(""))
    }

    // ---- Editing ----

    @Test
    fun `printable characters are inserted at the cursor`() {
        val state = ShellState()
        state.type("fiter")
        state.onKey("ArrowLeft")
        state.onKey("ArrowLeft")
        state.onKey("ArrowLeft")
        state.onKey("l")

        assertEquals("filter", state.input)
        assertEquals(3, state.cursor)
    }

    @Test
    fun `non-ASCII characters are typed like any other`() {
        val state = ShellState()
        state.type("caf")
        state.onKey("é")
        state.onKey("😀")

        assertEquals("café😀", state.input)
        assertEquals(state.input.length, state.cursor)
    }

    @Test
    fun `alt with a character types nothing`() {
        val state = ShellState()
        state.onKey("x", alt = true)

        assertEquals("", state.input)
    }

    @Test
    fun `backspace and delete remove the character before and under the cursor`() {
        val state = ShellState()
        state.type("abcd")
        state.onKey("Backspace")
        assertEquals("abc", state.input)

        state.onKey("Home")
        state.onKey("Backspace")
        assertEquals("abc", state.input)
        state.onKey("Delete")
        assertEquals("bc", state.input)
        assertEquals(0, state.cursor)

        state.onKey("End")
        state.onKey("Delete")
        assertEquals("bc", state.input)
        assertEquals(2, state.cursor)
    }

    @Test
    fun `arrows, home, end and ctrl-a, ctrl-e move the cursor and stop at the ends`() {
        val state = ShellState()
        state.type("abc")
        state.onKey("ArrowRight")
        assertEquals(3, state.cursor)
        state.onKey("Home")
        state.onKey("ArrowLeft")
        assertEquals(0, state.cursor)
        state.onKey("End")
        assertEquals(3, state.cursor)
        state.onKey("a", ctrl = true)
        assertEquals(0, state.cursor)
        state.onKey("e", ctrl = true)
        assertEquals(3, state.cursor)
    }

    @Test
    fun `ctrl-u deletes everything before the cursor`() {
        val state = ShellState()
        state.type("hello world")
        repeat(5) { state.onKey("ArrowLeft") }
        state.onKey("u", ctrl = true)

        assertEquals("world", state.input)
        assertEquals(0, state.cursor)
    }

    // ---- Ctrl-C, Ctrl-D, Enter ----

    @Test
    fun `ctrl-c clears the input, and quits on an empty one`() {
        val state = ShellState()
        state.type("abc")

        assertNull(state.onKey("c", ctrl = true))
        assertEquals("", state.input)
        assertEquals(ShellAction.Quit, state.onKey("c", ctrl = true))
    }

    @Test
    fun `ctrl-d quits only on an empty input`() {
        val state = ShellState()
        state.type("abc")

        assertNull(state.onKey("d", ctrl = true))
        assertEquals("abc", state.input)
        state.onKey("u", ctrl = true)
        assertEquals(ShellAction.Quit, state.onKey("D", ctrl = true))
    }

    @Test
    fun `enter runs trimmed input, clears it and records it`() {
        val state = ShellState()
        state.type("  pdf tools ")

        assertEquals(ShellAction.Run("pdf tools"), state.onKey("Enter"))
        assertEquals("", state.input)
        assertEquals(0, state.cursor)
        assertEquals(1, state.history.size)
    }

    @Test
    fun `enter on an empty input does nothing`() {
        val state = ShellState()
        state.type("   ")

        assertNull(state.onKey("Enter"))
        assertEquals(0, state.history.size)
    }

    @Test
    fun `other keys are ignored`() {
        val state = ShellState()
        state.type("ab")
        for (key in listOf("PageUp", "PageDown", "Insert", "F1")) assertNull(state.onKey(key))
        state.onKey("z", ctrl = true)

        assertEquals("ab", state.input)
        assertEquals(2, state.cursor)
    }

    // ---- Palette ----

    @Test
    fun `typing a slash opens the palette and typing filters it`() {
        val state = ShellState()
        assertEquals(emptyList(), state.palette)

        state.type("/")
        assertEquals(ShellCommands.ALL.size, state.palette.size)
        state.type("s")
        assertEquals(listOf("scan", "skill", "similar", "star", "stars", "serve", "unstar", "browse"), state.paletteNames)
        state.type("e")
        // "browse" contains "se", so it follows the prefix match.
        assertEquals(listOf("serve", "browse"), state.paletteNames)
    }

    @Test
    fun `arrows move the selection, stopping at the ends, and editing resets it`() {
        val state = ShellState()
        state.type("/s")
        state.onKey("ArrowUp")
        assertEquals(0, state.selection)
        repeat(9) { state.onKey("ArrowDown") }
        assertEquals(7, state.selection)
        state.onKey("ArrowUp")
        assertEquals(6, state.selection)

        state.type("i")
        assertEquals(0, state.selection)
    }

    @Test
    fun `tab completes the selected command`() {
        val state = ShellState()
        state.type("/sc")
        state.onKey("Tab")
        assertEquals("/scan ", state.input)
        assertEquals(6, state.cursor)
        assertEquals(emptyList(), state.palette)

        state.onKey("u", ctrl = true)
        state.type("/re")
        state.onKey("Tab")
        assertEquals("/repo", state.input)
    }

    @Test
    fun `tab with the palette closed does nothing`() {
        val state = ShellState()
        state.type("abc")
        state.onKey("Tab")

        assertEquals("abc", state.input)
    }

    @Test
    fun `enter runs a selected command without a required argument`() {
        val state = ShellState()
        state.type("/s")
        repeat(5) { state.onKey("ArrowDown") }

        assertEquals(ShellAction.Run("/serve"), state.onKey("Enter"))
        state.type("/h")
        assertEquals(ShellAction.Run("/help"), state.onKey("Enter"))
        assertEquals(listOf("/serve", "/help").size, state.history.size)
    }

    @Test
    fun `enter completes a selected command that needs an argument`() {
        val state = ShellState()
        state.type("/sk")

        assertNull(state.onKey("Enter"))
        assertEquals("/skill ", state.input)
    }

    @Test
    fun `escape closes the palette until the input is edited`() {
        val state = ShellState()
        state.type("/s")
        state.onKey("Escape")
        assertEquals(emptyList(), state.palette)
        assertEquals(ShellAction.Run("/s"), state.onKey("Enter"))

        state.type("/s")
        state.onKey("Escape")
        state.type("c")
        assertEquals(listOf("scan"), state.paletteNames)
        state.onKey("Escape")
        state.onKey("Backspace")
        assertEquals(8, state.palette.size)
    }

    @Test
    fun `skill names are suggested after skill and similar`() {
        val state = ShellState { skills }
        state.type("/skill ")
        assertEquals(listOf("mps-tests", "mps-run"), state.paletteNames)

        state.type("run")
        assertEquals(listOf("mps-run"), state.paletteNames)
        state.onKey("Tab")
        assertEquals("/skill mps-run", state.input)
        assertEquals(ShellAction.Run("/skill mps-run"), state.onKey("Enter"))
    }

    @Test
    fun `enter on a skill suggestion runs the command on it`() {
        val state = ShellState { skills }
        state.type("/similar mps")
        state.onKey("ArrowDown")

        assertEquals(ShellAction.Run("/similar mps-run"), state.onKey("Enter"))
    }

    @Test
    fun `without matching skills the input runs as typed`() {
        val state = ShellState { skills }
        state.type("/skill skills/mps-tests")

        assertEquals(emptyList(), state.palette)
        assertEquals(ShellAction.Run("/skill skills/mps-tests"), state.onKey("Enter"))
    }

    @Test
    fun `no skill suggestions without a repository`() {
        val state = ShellState { null }
        state.type("/skill ")

        assertEquals(emptyList(), state.palette)
    }

    // ---- History at the prompt ----

    @Test
    fun `up and down walk the history when the palette is closed`() {
        val state = ShellState()
        state.run("/scan a")
        state.run("pdf")
        state.type("dra")

        state.onKey("ArrowUp")
        assertEquals("pdf", state.input)
        state.onKey("ArrowUp")
        assertEquals("/scan a", state.input)
        assertEquals(7, state.cursor)
        state.onKey("ArrowUp")
        assertEquals("/scan a", state.input)
        state.onKey("ArrowDown")
        assertEquals("pdf", state.input)
        state.onKey("ArrowDown")
        assertEquals("dra", state.input)
    }

    @Test
    fun `a recalled command keeps the palette closed so the history keeps walking`() {
        val state = ShellState()
        state.run("/repo")
        state.run("/help")

        state.onKey("ArrowUp")
        assertEquals("/help", state.input)
        assertEquals(emptyList(), state.palette)
        state.onKey("ArrowUp")
        assertEquals("/repo", state.input)

        state.type("x")
        assertTrue(state.palette.isEmpty())
        state.onKey("Backspace")
        assertEquals(listOf("repo"), state.paletteNames)
    }

    @Test
    fun `editing stops walking the history`() {
        val state = ShellState()
        state.run("one")
        state.run("two")
        state.onKey("ArrowUp")
        state.type("!")

        assertNull(state.history.next())
        state.onKey("ArrowDown")
        assertEquals("two!", state.input)
    }
}

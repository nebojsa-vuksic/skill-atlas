package skillatlas

import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shell's live area: the prompt and the palette (spec section 5.9). */
class ShellScreenTest {
    private fun ShellState.type(text: String) = text.forEach { onKey(it.toString()) }

    /** A Mosaic snapshot of the live area, as plain text. */
    private fun snapshot(state: ShellState, columns: Int = 100): String = runBlocking {
        var text = ""
        runMosaicTest { text = setContentAndSnapshot { PromptArea(state, columns) } }
        text.lines().joinToString("\n") { it.trimEnd() }
    }

    @Test
    fun `the empty prompt shows the hint after the cursor`() {
        assertEquals("❯  type / for commands", snapshot(ShellState()))

        val line = ShellScreen.render(ShellState(), 100).single()
        assertEquals(listOf(Span("❯ ", Look.SELECTED), Span(" ", Look.CURSOR), Span("type / for commands", Look.DIM)), line)
    }

    @Test
    fun `typing shows the input with the cursor after it`() {
        val state = ShellState()
        state.type("pdf")
        val line = ShellScreen.render(state, 100).single()

        assertEquals("❯ pdf ", line.text)
        assertEquals(Span(" ", Look.CURSOR), line.last())

        state.onKey("Home")
        assertEquals(listOf(Span("❯ ", Look.SELECTED), Span("p", Look.CURSOR), Span("df ")), ShellScreen.render(state, 100).single())
    }

    @Test
    fun `a slash opens the palette with every command`() {
        val state = ShellState()
        state.type("/")

        assertEquals(
            """
            ❯ /
            ▸ /scan <url>              Scan a GitHub repository and make it the current one
              /filter <words>          List the skills whose name or description has every word
              /skill <name-or-path>    Show one skill: description, paths, similar skills, SKILL.md
              /similar <name-or-path>  Show the skills most similar to one skill
              /star <name-or-path>     Star a skill of the current repository
              /unstar <name-or-path>   Remove a skill's star
              /stars                   List every starred skill
              /browse                  Browse the current repository full-screen; q returns here
            """.trimIndent().lines().joinToString("\n") { it.trimEnd() },
            snapshot(state),
        )
    }

    @Test
    fun `the filtered palette highlights the matches and marks the selection`() {
        val state = ShellState()
        state.type("/il")
        state.onKey("ArrowDown")

        assertEquals(
            """
            ❯ /il
              /filter <words>          List the skills whose name or description has every word
            ▸ /skill <name-or-path>    Show one skill: description, paths, similar skills, SKILL.md
              /similar <name-or-path>  Show the skills most similar to one skill
            """.trimIndent(),
            snapshot(state),
        )
        val lines = ShellScreen.render(state, 100)
        assertEquals(listOf(Span("  ", Look.ACCENT), Span("/f", Look.ACCENT), Span("il", Look.HIGHLIGHT), Span("ter <words>", Look.ACCENT)), lines[1].take(4))
        assertEquals(listOf(Span("▸ ", Look.ACCENT), Span("/sk", Look.SELECTED), Span("il", Look.HIGHLIGHT)), lines[2].take(3))
        assertEquals(Span("Show the skills most similar to one skill", Look.DIM), lines[3].last())
    }

    @Test
    fun `the palette shows at most 8 rows and scrolls with the selection`() {
        val state = ShellState()
        state.type("/")
        assertEquals(1 + 8, ShellScreen.render(state, 100).size)

        repeat(ShellCommands.ALL.size) { state.onKey("ArrowDown") }
        val lines = ShellScreen.render(state, 100).map { it.text.trimEnd() }
        assertEquals(9, lines.size)
        assertTrue(lines[1].startsWith("  /unstar"), lines[1])
        assertTrue(lines[8].startsWith("▸ /quit"), lines[8])

        assertEquals(0 until 8, ShellScreen.window(10, 0))
        assertEquals(0 until 8, ShellScreen.window(10, 7))
        assertEquals(1 until 9, ShellScreen.window(10, 8))
        assertEquals(0 until 3, ShellScreen.window(3, 2))
    }

    @Test
    fun `skill suggestions show names and short descriptions`() {
        val skills = listOf(
            Skill("mps-tests", "Use when writing tests.", "skills/mps-tests"),
            Skill("docx", "Edit Word documents.", "skills/docx"),
        )
        val state = ShellState { skills }
        state.type("/skill mps")

        assertEquals(
            """
            ❯ /skill mps
            ▸ mps-tests  Use when writing tests.
            """.trimIndent(),
            snapshot(state),
        )
    }

    @Test
    fun `rows and the prompt fit the terminal width`() {
        val state = ShellState()
        state.type("/")
        val lines = ShellScreen.render(state, 40)

        assertTrue(lines.all { it.text.length <= 39 }, lines.joinToString("\n") { it.text })
        assertEquals("▸ /scan <url>              Scan a GitH…", lines[1].text)
    }

    @Test
    fun `long input scrolls sideways to keep the cursor visible`() {
        val state = ShellState()
        state.type("/scan https://github.com/anthropics/skills")
        val line = ShellScreen.render(state, 20).single()

        assertEquals("❯ …thropics/skills ", line.text)
        assertEquals(Span(" ", Look.CURSOR), line.last())

        state.onKey("Home")
        assertEquals("❯ /scan https://git", ShellScreen.render(state, 20).single().text)
    }
}

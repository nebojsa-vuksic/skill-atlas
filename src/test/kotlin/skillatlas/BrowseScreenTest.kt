package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `browse` layout at a fixed size (spec section 5.8). */
class BrowseScreenTest {
    private val sha = "49d37b63488a0a8e42eb0130cb867fd508f398ac"
    private val skills = listOf(
        Skill("mps-tests", "Use when writing or modifying tests inside MPS models.", ".agents/skills/mps-tests", alsoAt = listOf(".claude/skills/mps-tests"), shipped = true),
        Skill("pdf", "Extract text from PDF files.", "skills/pdf", listOf("missing name")),
    )
    private val result = ScanResult(
        RepositoryMetadata("JetBrains/MPS", null, "master"), "master", sha, skills,
        contents = mapOf(".agents/skills/mps-tests" to "---\nname: mps-tests\n---\n\n# MPS tests\n\n  - indented item that is long enough to wrap around\n"),
    )
    private val similar = mapOf(
        ".agents/skills/mps-tests" to listOf(SimilarSkill("skills/pdf", "pdf", 42)),
        "skills/pdf" to emptyList(),
    )

    private fun screen(state: BrowseState = BrowseState(result, similar), columns: Int = 80, rows: Int = 20) =
        BrowseScreen.render(state, columns, rows).lines.map { it.text }

    @Test
    fun `lays out the list, the selected skill and the key help`() {
        val lines = screen()

        assertEquals(20, lines.size)
        assertTrue(lines.all { it.length == 80 }, lines.joinToString("\n"))
        assertEquals(
            listOf(
                " SKILL ATLAS   JetBrains/MPS  49d37b63488a master",
                " / filter                2 of 2 │ mps-tests  ◆ shipped in product",
                "────────────────────────────────│ Use when writing or modifying tests inside MPS",
                "▌mps-tests                 ◆ ⧉1 │ models.",
                "▌Use when writing or modifying… │ .agents/skills/mps-tests",
                "                                │ also in .claude/skills/mps-tests",
                " pdf                          ⚠ │",
                " Extract text from PDF files.   │ Similar skills",
                "                                │   pdf  ████░░░░░░  42 %",
                "                                │",
                "                                │ SKILL.md",
                "                                │ ---",
                "                                │ name: mps-tests",
                "                                │ ---",
                "                                │",
                "                                │ # MPS tests",
                "                                │",
                "                                │   - indented item that is long enough to wrap",
                "                                │   around",
                " ↑↓ select  / filter  s star  tab similar  pgup/pgdn scroll  esc clear  q quit",
            ),
            lines.map { it.trimEnd() },
        )
    }

    @Test
    fun `shows the filter cursor, the active count and highlights`() {
        val state = BrowseState(result, similar)
        state.onKey("/")
        "pdf".forEach { state.onKey(it.toString()) }

        val frame = BrowseScreen.render(state, 80, 20)
        val lines = frame.lines.map { it.text.trimEnd() }

        assertEquals(" / pdf▏                  1 of 2 │ pdf  ⚠ missing name", lines[1])
        assertEquals("▌pdf                          ⚠ │ skills/pdf", lines[3])
        assertEquals("▌Extract text from PDF files.   │", lines[4])
        assertTrue(frame.lines[3].any { it.look == Look.HIGHLIGHT && it.text == "pdf" })
        assertTrue(frame.lines[1].any { it.look == Look.COUNT })
        assertEquals(" type to filter  enter/↓ done  esc clear  backspace delete", lines.last())
    }

    @Test
    fun `says when nothing matches`() {
        val state = BrowseState(result, similar)
        state.onKey("/")
        "zzz".forEach { state.onKey(it.toString()) }

        val lines = screen(state)

        assertEquals(" No skills match \"zzz\".", lines[3].substringBefore("│").trimEnd())
        assertTrue(lines.drop(1).dropLast(1).all { it.substringAfter("│").isBlank() })
    }

    @Test
    fun `marks the similar skill cursor`() {
        val state = BrowseState(result, similar)
        state.onKey("Tab")

        assertEquals("│ ▸ pdf  ████░░░░░░  42 %", screen(state)[8].substring(32).trimEnd())
        assertEquals(" ↑↓ move  enter open  tab/esc back", screen(state).last().trimEnd())
    }

    @Test
    fun `reports how far the right pane can scroll`() {
        val frame = BrowseScreen.render(BrowseState(result, similar), 80, 12)
        assertEquals(10, frame.page)
        // 18 lines on the right (name, 2 description, path, copy, blank, heading, row, blank, heading, 8 of content) in 10 rows.
        assertEquals(8, frame.maxScroll)
    }

    @Test
    fun `asks for a bigger terminal below the minimum size`() {
        assertEquals(
            listOf("Terminal too small: browse needs at least 60×10."),
            screen(columns = 59, rows = 20).map { it.trimEnd() },
        )
    }

    @Test
    fun `fits and wraps text`() {
        assertEquals("abc…", BrowseScreen.fit(listOf(Span("abcdef")), 4).text)
        assertEquals("ab  ", BrowseScreen.fit(listOf(Span("ab")), 4).text)
        assertEquals(listOf("one two", "three", "abcde", "fgh"), BrowseScreen.wrap("one two three abcdefgh", 7).let { it.take(2) + BrowseScreen.wrap("abcdefgh", 5) })
    }

    @Test
    fun `marks starred skills and shows a notice in place of the key help`() {
        val starred = result.copy(skills = skills.map { it.copy(starred = it.name == "mps-tests") })
        val state = BrowseState(starred, similar, notice = Span("error: could not save stars /x: permission denied", Look.ERROR))
        val frame = BrowseScreen.render(state, 80, 20)
        val lines = frame.lines.map { it.text.trimEnd() }

        assertEquals("▌mps-tests               ★ ◆ ⧉1 │ models.", lines[3])
        assertEquals(" / filter                2 of 2 │ mps-tests  ★ starred  ◆ shipped in product", lines[1])
        assertEquals(Span("★", Look.STAR), frame.lines[3].first { it.text == "★" })
        assertEquals(" error: could not save stars /x: permission denied", lines.last())
        assertEquals(Look.ERROR, frame.lines.last().first().look)

        state.onKey("ArrowDown")
        assertTrue(BrowseScreen.render(state, 80, 20).lines.last().text.startsWith(" ↑↓ select  / filter  s star"))
    }
}

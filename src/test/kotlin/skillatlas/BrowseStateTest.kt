package skillatlas

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Every key of `browse` in every focus area (spec sections 5.8 and 5.11). */
class BrowseStateTest {
    private val skills = listOf(
        Skill("commits", "How to write commit messages.", "skills/commits"),
        Skill("mps-tests", "Write MPS tests.", "skills/mps-tests"),
        Skill("mps-typesystem", "Define typesystem rules.", "skills/mps-typesystem"),
        Skill("pdf", "Extract text from PDF files.", "skills/pdf"),
    )
    private val similar = mapOf(
        "skills/commits" to emptyList(),
        "skills/mps-tests" to listOf(SimilarSkill("skills/mps-typesystem", "mps-typesystem", 40), SimilarSkill("skills/pdf", "pdf", 6)),
        "skills/mps-typesystem" to listOf(SimilarSkill("skills/mps-tests", "mps-tests", 40)),
        "skills/pdf" to listOf(SimilarSkill("skills/mps-tests", "mps-tests", 6)),
    )

    private fun state() = BrowseState(ScanResult(RepositoryMetadata("acme/skills", null, "main"), "main", "c".repeat(40), skills), similar)

    private fun BrowseState.type(text: String) = text.forEach { onKey(it.toString()) }

    @Test
    fun `starts on the first skill with the list focused`() {
        val state = state()
        assertEquals("skills/commits", state.selectedPath)
        assertEquals(BrowseFocus.LIST, state.focus)
        assertEquals(4, state.visible.size)
    }

    @Test
    fun `moves the selection within the visible skills`() {
        val state = state()
        state.onKey("ArrowDown")
        state.onKey("ArrowDown")
        assertEquals("skills/mps-typesystem", state.selectedPath)
        state.onKey("End")
        assertEquals("skills/pdf", state.selectedPath)
        state.onKey("ArrowDown")
        assertEquals("skills/pdf", state.selectedPath)
        state.onKey("Home")
        assertEquals("skills/commits", state.selectedPath)
        state.onKey("ArrowUp")
        assertEquals("skills/commits", state.selectedPath)
    }

    @Test
    fun `slash focuses the filter, typing filters, and enter returns to the list`() {
        val state = state()
        state.onKey("/")
        assertEquals(BrowseFocus.FILTER, state.focus)
        state.type("mps")
        assertEquals("mps", state.query)
        assertEquals(listOf("mps-tests", "mps-typesystem"), state.visible.map { it.name })
        assertEquals("skills/mps-tests", state.selectedPath, "the hidden selection moves to the first visible skill")
        state.onKey("Enter")
        assertEquals(BrowseFocus.LIST, state.focus)
        assertEquals("mps", state.query)
    }

    @Test
    fun `typing q in the filter is text, not quit`() {
        val state = state()
        state.onKey("/")
        state.type("q")
        assertEquals("q", state.query)
        assertFalse(state.quit)
    }

    @Test
    fun `backspace edits and escape clears the filter`() {
        val state = state()
        state.onKey("/")
        state.type("pdfx")
        assertTrue(state.visible.isEmpty())
        state.onKey("Backspace")
        assertEquals("pdf", state.query)
        assertEquals(listOf("pdf"), state.visible.map { it.name })
        state.onKey("Escape")
        assertEquals("", state.query)
        assertEquals(BrowseFocus.LIST, state.focus)
        assertEquals("skills/pdf", state.selectedPath, "clearing keeps the selection")
    }

    @Test
    fun `arrow down leaves the filter`() {
        val state = state()
        state.onKey("/")
        state.onKey("ArrowDown")
        assertEquals(BrowseFocus.LIST, state.focus)
    }

    @Test
    fun `keeps the selection while nothing matches, and restores it when cleared`() {
        val state = state()
        state.onKey("End")
        state.onKey("/")
        state.type("zzz")
        assertNull(state.selected)
        assertEquals("skills/pdf", state.selectedPath)
        state.onKey("Escape")
        assertEquals("pdf", state.selected?.name)
    }

    @Test
    fun `escape in the list clears the filter`() {
        val state = state()
        state.onKey("/")
        state.type("pdf")
        state.onKey("Enter")
        state.onKey("Escape")
        assertEquals("", state.query)
    }

    @Test
    fun `tab focuses similar skills only when there are some`() {
        val state = state()
        state.onKey("Tab")
        assertEquals(BrowseFocus.LIST, state.focus, "commits has no similar skills")
        state.onKey("ArrowDown")
        state.onKey("Tab")
        assertEquals(BrowseFocus.SIMILAR, state.focus)
        assertEquals(0, state.similarCursor)
        state.onKey("ArrowDown")
        state.onKey("ArrowDown")
        assertEquals(1, state.similarCursor)
        state.onKey("ArrowUp")
        assertEquals(0, state.similarCursor)
        state.onKey("Escape")
        assertEquals(BrowseFocus.LIST, state.focus)
    }

    @Test
    fun `enter on a similar skill selects it and goes back to the list`() {
        val state = state()
        state.onKey("ArrowDown")
        state.onKey("Tab")
        state.onKey("Enter")
        assertEquals("skills/mps-typesystem", state.selectedPath)
        assertEquals(BrowseFocus.LIST, state.focus)
    }

    @Test
    fun `enter on a similar skill that the filter hides clears the filter`() {
        val state = state()
        state.onKey("/")
        state.type("tests")
        state.onKey("Enter")
        assertEquals("skills/mps-tests", state.selectedPath)
        state.onKey("Tab")
        state.onKey("ArrowDown")
        state.onKey("Enter")
        assertEquals("", state.query)
        assertEquals("skills/pdf", state.selectedPath)
    }

    @Test
    fun `enter on a similar skill that is still visible keeps the filter`() {
        val state = state()
        state.onKey("/")
        state.type("mps")
        state.onKey("Enter")
        state.onKey("Tab")
        state.onKey("Enter")
        assertEquals("mps", state.query)
        assertEquals("skills/mps-typesystem", state.selectedPath)
    }

    @Test
    fun `pages the right pane and scrolls back up when the selection changes`() {
        val state = state()
        state.onKey("PageDown", page = 10, maxScroll = 25)
        assertEquals(9, state.scroll)
        state.onKey("PageDown", page = 10, maxScroll = 25)
        state.onKey("PageDown", page = 10, maxScroll = 25)
        assertEquals(25, state.scroll)
        state.onKey("PageUp", page = 10, maxScroll = 25)
        assertEquals(16, state.scroll)
        state.onKey("ArrowDown")
        assertEquals(0, state.scroll)
    }

    @Test
    fun `q and ctrl-c quit`() {
        assertTrue(state().also { it.onKey("q") }.quit)
        assertTrue(state().also { it.onKey("/"); it.onKey("c", ctrl = true) }.quit)
        assertTrue(state().also { it.onKey("ArrowDown"); it.onKey("Tab"); it.onKey("c", ctrl = true) }.quit)
    }

    @Test
    fun `reports unused keys as not handled`() {
        val state = state()
        assertFalse(state.onKey("F5"))
        assertFalse(state.onKey("x"))
        state.onKey("/")
        assertFalse(state.onKey("Tab"))
    }

    private fun starredState(dir: Path): Pair<BrowseState, StarStore> {
        val stars = StarStore(dir.resolve("stars.json"))
        stars.star("acme/skills", skills[1])
        val result = ScanResult(RepositoryMetadata("acme/skills", null, "main"), "main", "c".repeat(40), skills).withStars(stars.read())
        return BrowseState(result, similar, stars) to stars
    }

    @Test
    fun `s stars and unstars the selected skill and saves it at once`(@TempDir dir: Path) {
        val (state, stars) = starredState(dir)
        assertEquals(listOf(false, true, false, false), state.result.skills.map { it.starred })

        assertTrue(state.onKey("s"))
        assertTrue(state.selected!!.starred)
        assertEquals(listOf("acme/skills:skills/commits", "acme/skills:skills/mps-tests"), stars.read().map { it.id })

        state.onKey("ArrowDown")
        state.onKey("s")
        assertFalse(state.selected!!.starred)
        assertEquals(listOf("acme/skills:skills/commits"), stars.read().map { it.id })
    }

    @Test
    fun `with is starred in the filter, an unstarred skill leaves the list`(@TempDir dir: Path) {
        val (state, stars) = starredState(dir)
        stars.star("acme/skills", skills[3])
        val both = BrowseState(state.result.withStars(stars.read()), similar, stars)
        both.onKey("/")
        both.type("is:starred")
        both.onKey("Enter")
        assertEquals(listOf("mps-tests", "pdf"), both.visible.map { it.name })
        assertEquals("skills/mps-tests", both.selectedPath)

        both.onKey("s")

        assertEquals(listOf("pdf"), both.visible.map { it.name })
        assertEquals("skills/pdf", both.selectedPath)
        // The filter's own words aren't typed into the list: s in the filter is a letter.
        both.onKey("/")
        both.onKey("s")
        assertEquals("is:starreds", both.query)
    }

    @Test
    fun `a stars file error shows in the bottom line until the next key`(@TempDir dir: Path) {
        val file = dir.resolve("stars.json").also { Files.writeString(it, "{broken") }
        val state = BrowseState(ScanResult(RepositoryMetadata("acme/skills", null, "main"), "main", "c".repeat(40), skills), similar, StarStore(file))

        state.onKey("s")

        assertEquals(Span("error: could not read stars $file: not valid stars JSON", Look.ERROR), state.notice)
        assertFalse(state.selected!!.starred)
        assertEquals("{broken", Files.readString(file))
        state.onKey("ArrowDown")
        assertNull(state.notice)
    }

    @Test
    fun `s does nothing without a stars file`() {
        val state = state()
        assertFalse(state.onKey("s"))
        assertFalse(state.selected!!.starred)
    }
}

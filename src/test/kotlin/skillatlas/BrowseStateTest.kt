package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every key of `browse` in every focus area (spec section 5.8). */
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
}

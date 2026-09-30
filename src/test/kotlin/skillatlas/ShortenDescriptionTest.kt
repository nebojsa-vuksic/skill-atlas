package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortenDescriptionTest {
    @Test
    fun `keeps short descriptions and collapses whitespace`() {
        assertEquals("Extract text from PDFs.", shortenDescription("Extract text from PDFs."))
        assertEquals("One two three", shortenDescription("  One\n  two\t\tthree \n"))
        assertEquals("", shortenDescription(""))
    }

    @Test
    fun `keeps a description of exactly the limit`() {
        val exact = "a".repeat(MAX_DESCRIPTION_LENGTH)
        assertEquals(exact, shortenDescription(exact))
    }

    @Test
    fun `cuts at the last word boundary and appends an ellipsis`() {
        assertEquals("The quick brown…", shortenDescription("The quick brown fox jumps", maxLength = 18))
        assertEquals("The quick brown fox…", shortenDescription("The quick brown fox jumps", maxLength = 19))
    }

    @Test
    fun `drops trailing punctuation before the ellipsis`() {
        assertEquals("Reads PDFs…", shortenDescription("Reads PDFs, and writes them", maxLength = 14))
        assertEquals("Reads PDFs…", shortenDescription("Reads PDFs — then writes them", maxLength = 14))
    }

    @Test
    fun `hard-cuts a single long word`() {
        assertEquals("abcdefghij…", shortenDescription("abcdefghijklmnop", maxLength = 10))
    }

    @Test
    fun `never splits a surrogate pair`() {
        val shortened = shortenDescription("abcdefghi😀xyz", maxLength = 10)
        assertEquals("abcdefghi…", shortened)
    }

    @Test
    fun `never exceeds the limit plus the ellipsis`() {
        val text = (1..60).joinToString(" ") { "word$it" }
        for (limit in 10..MAX_DESCRIPTION_LENGTH) {
            val shortened = shortenDescription(text, limit)
            assertTrue(shortened.length <= limit + 1 && shortened.endsWith("…"), "limit=$limit: $shortened")
        }
    }
}

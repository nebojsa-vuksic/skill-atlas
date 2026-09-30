package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMarkdownTest {
    @Test
    fun `renders the body after the frontmatter`() {
        val file = "---\nname: pdf\ndescription: d\n---\n\n# PDF\n\nUse `pdftotext`, then **check** it.\n"

        assertEquals("<h1>PDF</h1>\n<p>Use <code>pdftotext</code>, then <strong>check</strong> it.</p>\n", SkillMarkdown.render(file))
    }

    @Test
    fun `renders the whole file when there is no frontmatter`() {
        assertEquals("<h2>Notes</h2>\n", SkillMarkdown.render("## Notes\n"))
    }

    @Test
    fun `renders GitHub-style tables`() {
        val html = SkillMarkdown.render("| Aspect | File |\n|---|---|\n| Editor | editor.mps |\n")

        assertTrue("<table>" in html && "<th>Aspect</th>" in html && "<td>editor.mps</td>" in html, html)
    }

    @Test
    fun `escapes raw HTML instead of rendering it`() {
        val html = SkillMarkdown.render("Hello <script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n")

        assertTrue("<script>" !in html && "<img" !in html, html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html, html)
    }

    @Test
    fun `drops unsafe link targets and keeps safe ones`() {
        val html = SkillMarkdown.render(
            "[bad](javascript:alert(1)) [web](https://example.com) [mail](mailto:a@b.c) [doc](reference.md)\n",
        )

        assertTrue("javascript:" !in html, html)
        assertTrue("""href="https://example.com"""" in html, html)
        assertTrue("""href="mailto:a@b.c"""" in html, html)
        assertTrue("""href="reference.md"""" in html, html)
    }

    @Test
    fun `extracts the body`() {
        assertEquals("# Title\n", SkillParser.body("﻿---\nname: a\n---\n\n# Title\n"))
        assertEquals("no frontmatter\n", SkillParser.body("no frontmatter\n"))
        assertEquals("---\nname: a\nnever closed\n", SkillParser.body("---\nname: a\nnever closed\n"))
    }
}

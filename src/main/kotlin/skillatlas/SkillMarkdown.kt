package skillatlas

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders a skill file's Markdown body for the web view (spec section 5.4). Skill files
 * come from untrusted repositories, so raw HTML is escaped rather than passed through,
 * and link targets other than http, https and mailto are dropped.
 */
object SkillMarkdown {
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .build()

    /** HTML for the Markdown after the frontmatter of [skillFile]. */
    fun render(skillFile: String): String = renderer.render(parser.parse(SkillParser.body(skillFile)))
}

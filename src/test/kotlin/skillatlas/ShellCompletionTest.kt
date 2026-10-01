package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals

/** Palette matching and ranking, and what the palette offers for an input (spec section 5.9). */
class ShellCompletionTest {
    private val skills = listOf(
        Skill("pdf-extract", "Extract text from PDF files.", "skills/pdf-extract"),
        Skill("pdf", "Read PDF files.", "skills/pdf"),
        Skill("review", "Review pull requests.", ".agents/skills/review"),
        Skill("Review", "Review documents.", "docs/review"),
        Skill("docx", "Edit Word documents.", "skills/docx"),
    )

    private fun names(query: String, candidates: List<String>) = PaletteMatcher.rank(query, candidates).map { it.text }

    private fun labels(input: String, skills: List<Skill>? = null) = ShellCompletion.suggest(input, skills).map {
        when (it) {
            is PaletteItem.Command -> it.command.label
            is PaletteItem.SkillArgument -> it.match.text
        }
    }

    @Test
    fun `ranks exact matches, then prefixes, then substrings, keeping the order within each`() {
        val candidates = listOf("repo-tools", "prepare", "rep", "report", "sharp")

        assertEquals(listOf("rep", "repo-tools", "report", "prepare"), names("rep", candidates))
    }

    @Test
    fun `matching ignores case and records where the text matched`() {
        val matches = PaletteMatcher.rank("SIM", listOf("scan", "similar", "facsimile"))

        assertEquals(listOf("similar", "facsimile"), matches.map { it.text })
        assertEquals(listOf(0..2, 3..5), matches.map { it.range })
        assertEquals(listOf(1, 2), matches.map { it.index })
    }

    @Test
    fun `an empty query lists everything in order without highlights`() {
        val matches = PaletteMatcher.rank("", listOf("b", "a"))

        assertEquals(listOf("b", "a"), matches.map { it.text })
        assertEquals(listOf(null, null), matches.map { it.range })
    }

    @Test
    fun `a slash alone lists every command in registry order`() {
        assertEquals(
            listOf(
                "/scan <url>", "/filter <words>", "/skill <name-or-path>", "/similar <name-or-path>",
                "/star <name-or-path>", "/unstar <name-or-path>", "/stars", "/browse", "/repo", "/serve [port]", "/log", "/help", "/quit",
            ),
            labels("/"),
        )
    }

    @Test
    fun `commands are filtered as you type`() {
        assertEquals(
            listOf(
                "/scan <url>", "/skill <name-or-path>", "/similar <name-or-path>", "/star <name-or-path>", "/stars", "/serve [port]",
                "/unstar <name-or-path>", "/browse",
            ),
            labels("/s"),
        )
        assertEquals(listOf("/scan <url>"), labels("/SC"))
        // "il" only occurs inside names, so these are substring matches.
        assertEquals(listOf("/filter <words>", "/skill <name-or-path>", "/similar <name-or-path>"), labels("/il"))
        assertEquals(emptyList(), labels("/zzz"))
    }

    @Test
    fun `commands complete with a space only when they take an argument`() {
        val items = ShellCompletion.suggest("/", null).filterIsInstance<PaletteItem.Command>().associate { it.command.name to it.completion }

        assertEquals("/scan ", items["scan"])
        assertEquals("/serve ", items["serve"])
        assertEquals("/repo", items["repo"])
        assertEquals("/quit", items["quit"])
    }

    @Test
    fun `no palette for plain text or for arguments of other commands`() {
        assertEquals(emptyList(), labels("pdf", skills))
        assertEquals(emptyList(), labels("/scan github.com/a/b", skills))
        assertEquals(emptyList(), labels("/filter p", skills))
        assertEquals(emptyList(), labels("/nope p", skills))
    }

    @Test
    fun `skill and similar suggest the current repository's skills`() {
        assertEquals(listOf("pdf", "pdf-extract"), labels("/skill pdf", skills))
        assertEquals(listOf("docx"), labels("/similar DOCX", skills))
        assertEquals(listOf("pdf", "pdf-extract"), labels("/SKILL   pdf", skills))
    }

    @Test
    fun `star and unstar suggest skills too`() {
        assertEquals(listOf("pdf", "pdf-extract"), labels("/star pdf", skills))
        assertEquals("/unstar docx", (ShellCompletion.suggest("/unstar DOCX", skills).single() as PaletteItem.SkillArgument).completion)
        assertEquals(emptyList(), labels("/stars ", skills))
    }

    @Test
    fun `a name that several skills share is offered as their paths`() {
        assertEquals(listOf("pdf-extract", "pdf", ".agents/skills/review", "docs/review", "docx"), labels("/skill ", skills))
        assertEquals(listOf(".agents/skills/review", "docs/review"), labels("/skill rev", skills))
    }

    @Test
    fun `skill suggestions carry the shortened description and complete the whole command`() {
        val item = ShellCompletion.suggest("/similar docx", skills).single() as PaletteItem.SkillArgument

        assertEquals("Edit Word documents.", item.description)
        assertEquals("/similar docx", item.completion)
    }

    @Test
    fun `no skill suggestions without a repository`() {
        assertEquals(emptyList(), labels("/skill ", null))
    }
}

package skillatlas

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class SkillParserTest {
    @TempDir
    lateinit var root: Path

    private fun parse(content: String, relativePath: String = "skills/pdf/SKILL.md"): Skill {
        val file = root.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return SkillParser.parse(root, root.relativize(file), rootName = "repo")
    }

    @Test
    fun `reads name and description`() {
        val skill = parse("---\nname: pdf-extract\ndescription: Extract text from PDFs.\n---\n\n# PDF\n")
        assertEquals(Skill("pdf-extract", "Extract text from PDFs.", "skills/pdf"), skill)
    }

    @Test
    fun `ignores other keys and trims the description`() {
        val skill = parse("---\nname: a\nlicense: MIT\ndescription: \"  padded  \"\nallowed-tools: [Bash]\n---\n")
        assertEquals(Skill("a", "padded", "skills/pdf"), skill)
    }

    @Test
    fun `accepts a BOM and CRLF line endings`() {
        val skill = parse("﻿---\r\nname: a\r\ndescription: b\r\n---\r\nbody\r\n")
        assertEquals(Skill("a", "b", "skills/pdf"), skill)
    }

    @Test
    fun `keeps multi-line block descriptions`() {
        val skill = parse("---\nname: a\ndescription: |\n  First line.\n  Second line.\n---\n")
        assertEquals("First line.\nSecond line.", skill.description)
        assertEquals(emptyList(), skill.warnings)
    }

    @Test
    fun `folds folded descriptions`() {
        val skill = parse("---\nname: a\ndescription: >\n  One\n  sentence.\n---\n")
        assertEquals("One sentence.", skill.description)
    }

    @Test
    fun `falls back to the directory name when the name is missing or empty`() {
        assertEquals(Skill("pdf", "d", "skills/pdf", listOf(SkillParser.MISSING_NAME)), parse("---\ndescription: d\n---\n"))
        assertEquals(Skill("pdf", "d", "skills/pdf", listOf(SkillParser.MISSING_NAME)), parse("---\nname: \"\"\ndescription: d\n---\n"))
    }

    @Test
    fun `warns about a missing description`() {
        assertEquals(Skill("a", "", "skills/pdf", listOf(SkillParser.MISSING_DESCRIPTION)), parse("---\nname: a\n---\n"))
    }

    @Test
    fun `warns about both for empty frontmatter`() {
        val skill = parse("---\n---\n")
        assertEquals(listOf(SkillParser.MISSING_NAME, SkillParser.MISSING_DESCRIPTION), skill.warnings)
    }

    @Test
    fun `converts non-string scalars to text`() {
        assertEquals("2048", parse("---\nname: 2048\ndescription: d\n---\n").name)
    }

    @Test
    fun `reports invalid frontmatter but still lists the skill`() {
        val invalid = listOf(
            "# No frontmatter\n",
            "---\nname: a\ndescription: never closed\n",
            "---\nname: [unclosed\n---\n",
            "---\n- a list\n- not a map\n---\n",
            "\n---\nname: a\n---\n",
        )
        for (content in invalid) {
            assertEquals(Skill("pdf", "", "skills/pdf", listOf(SkillParser.INVALID_FRONTMATTER)), parse(content), content)
        }
    }

    @Test
    fun `reports invalid UTF-8 as invalid frontmatter`() {
        val file = root.resolve("bad/SKILL.md")
        file.parent.createDirectories()
        file.writeBytes(byteArrayOf(0x2D, 0x2D, 0x2D, 0x0A, 0xC3.toByte(), 0x28, 0x0A, 0x2D, 0x2D, 0x2D))
        assertEquals(listOf(SkillParser.INVALID_FRONTMATTER), SkillParser.parse(root, root.relativize(file), "repo").warnings)
    }

    @Test
    fun `skips files larger than 1 MB`() {
        val skill = parse("---\nname: a\ndescription: b\n---\n" + "x".repeat(SkillParser.MAX_FILE_SIZE.toInt()))
        assertEquals(Skill("pdf", "", "skills/pdf", listOf(SkillParser.FILE_TOO_LARGE)), skill)
    }

    @Test
    fun `names a root skill after the repository`() {
        val skill = parse("---\ndescription: d\n---\n", relativePath = "SKILL.md")
        assertEquals(Skill("repo", "d", ".", listOf(SkillParser.MISSING_NAME)), skill)
    }
}

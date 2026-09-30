package skillatlas

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class SkillScannerTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private fun touch(relativePath: String, base: Path = root) {
        val file = base.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText("---\nname: x\ndescription: y\n---\n")
    }

    private fun discovered() = SkillScanner.discover(root).map { it.invariantSeparatorsPathString }

    @Test
    fun `finds skill files at any depth, sorted by path`() {
        touch("skills/zeta/SKILL.md")
        touch("SKILL.md")
        touch("skills/alpha/SKILL.md")
        touch("plugins/p/skills/beta/SKILL.md")
        touch("skills/alpha/README.md")

        assertEquals(
            listOf("SKILL.md", "plugins/p/skills/beta/SKILL.md", "skills/alpha/SKILL.md", "skills/zeta/SKILL.md"),
            discovered(),
        )
    }

    @Test
    fun `matches the file name case-sensitively`() {
        touch("upper/SKILL.md")
        touch("lower/skill.md")
        touch("mixed/Skill.md")

        assertEquals(listOf("upper/SKILL.md"), discovered())
    }

    @Test
    fun `skips excluded directories at any depth`() {
        for (dir in SkillScanner.SKIPPED_DIRECTORIES) {
            touch("$dir/a/SKILL.md")
            touch("nested/$dir/SKILL.md")
        }
        touch("kept/SKILL.md")

        assertEquals(listOf("kept/SKILL.md"), discovered())
    }

    @Test
    fun `does not follow symbolic links`() {
        touch("real/SKILL.md")
        touch("elsewhere/SKILL.md", base = outside)
        Files.createSymbolicLink(root.resolve("linked-dir"), outside.resolve("elsewhere"))
        root.resolve("linked-file").createDirectories()
        Files.createSymbolicLink(root.resolve("linked-file/SKILL.md"), outside.resolve("elsewhere/SKILL.md"))
        Files.createSymbolicLink(root.resolve("loop"), root)

        assertEquals(listOf("real/SKILL.md"), discovered())
    }

    @Test
    fun `returns nothing for a repository without skills`() {
        touch("docs/README.md")
        assertEquals(emptyList(), discovered())
    }
}

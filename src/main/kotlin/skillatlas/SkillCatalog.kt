package skillatlas

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString

/** A skill file that is not a skill of the repository, and why. */
data class IgnoredSkill(val path: String, val reason: String)

/** Where a skill file sits in the repository, which decides how it is reported (spec section 4.4). */
enum class SkillLocation {
    /** An agent configuration folder at the repository root, such as `.agents` or `.claude`. */
    AGENT_CONFIG,

    /** Anywhere else in the repository's own tree, such as `agent/skills`. */
    REPOSITORY,

    /** Inside a `resources` folder, so the skill ships with the product. */
    PRODUCT,

    /** Inside a test source set or fixture folder: test data, not a skill. */
    TEST_DATA;

    companion object {
        private val TEST_DIRECTORIES = setOf(
            "test", "tests", "testdata", "test-data", "fixtures", "__fixtures__", "__tests__",
        )

        /** Classifies a skill directory, given relative to the repository root with `/` separators. */
        fun of(skillDirectory: String): SkillLocation {
            // The skill's own directory name never counts, so a skill called "tests" is still a skill.
            val parents = if (skillDirectory == ".") emptyList() else skillDirectory.split('/').dropLast(1)
            return when {
                parents.withIndex().any { (i, name) -> isTestDirectory(name, parents.getOrNull(i - 1)) } -> TEST_DATA
                "resources" in parents -> PRODUCT
                parents.firstOrNull()?.startsWith(".") == true -> AGENT_CONFIG
                else -> REPOSITORY
            }
        }

        private fun isTestDirectory(name: String, parent: String?) =
            name.lowercase() in TEST_DIRECTORIES ||
                // Gradle and Kotlin source sets: src/test, src/jvmTest, src/integrationTest, src/testFixtures, …
                (parent == "src" && name.contains("test", ignoreCase = true))
    }
}

/** The skills of a repository after handling duplicates, product copies and test data (spec section 4.4). */
data class Catalog(val skills: List<Skill>, val ignored: List<IgnoredSkill>)

object SkillCatalog {
    const val TEST_DATA = "test data"
    const val DUPLICATE_NAME = "duplicate name"

    private class Copy(val skill: Skill, val location: SkillLocation, val contentHash: String?)

    /** Builds the catalog from [skillFiles], paths relative to [root] as returned by [SkillScanner.discover]. */
    fun build(root: Path, skillFiles: List<Path>, rootName: String): Catalog {
        val ignored = mutableListOf<IgnoredSkill>()
        val copies = mutableListOf<Copy>()
        for (file in skillFiles) {
            val directory = file.parent?.invariantSeparatorsPathString ?: "."
            val location = SkillLocation.of(directory)
            if (location == SkillLocation.TEST_DATA) {
                ignored += IgnoredSkill(directory, TEST_DATA)
                continue
            }
            copies += Copy(SkillParser.parse(root, file, rootName), location, contentHash(root.resolve(file)))
        }

        // Byte-for-byte identical skill files are one skill with several copies.
        val merged = copies
            .groupBy { it.contentHash ?: "unhashed:${it.skill.path}" }
            .values
            .map { group ->
                val ordered = group.sortedWith(compareBy<Copy> { it.location.ordinal }.thenBy { it.skill.path })
                ordered.first().skill.copy(
                    alsoAt = ordered.drop(1).map { it.skill.path },
                    shipped = group.any { it.location == SkillLocation.PRODUCT },
                )
            }

        // Different skills that share a name are both listed, and flagged.
        val nameCounts = merged.groupingBy { it.name }.eachCount()
        val skills = merged
            .map { if (nameCounts.getValue(it.name) > 1) it.copy(warnings = it.warnings + DUPLICATE_NAME) else it }
            .sortedBy { it.path }
        return Catalog(skills, ignored.sortedBy { it.path })
    }

    /** SHA-256 of the file, or null when it is too large or unreadable, so it is never merged. */
    private fun contentHash(file: Path): String? = try {
        if (Files.size(file) > SkillParser.MAX_FILE_SIZE) {
            null
        } else {
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        }
    } catch (_: IOException) {
        null
    }
}

package skillatlas

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

data class Skill(
    val name: String,
    val description: String,
    /** The skill's directory relative to the repository root, `.` for the root itself. */
    val path: String,
    val warnings: List<String> = emptyList(),
)

/** Reads a skill's name and description from its frontmatter (spec section 4.3). */
object SkillParser {
    const val MAX_FILE_SIZE = 1024L * 1024

    const val MISSING_NAME = "missing name"
    const val MISSING_DESCRIPTION = "missing description"
    const val INVALID_FRONTMATTER = "invalid frontmatter"
    const val FILE_TOO_LARGE = "file too large"
    const val UNREADABLE_FILE = "unreadable file"

    /**
     * Parses [skillFile], a path relative to [root]. [rootName] names a skill whose
     * `SKILL.md` sits at the repository root, where there is no enclosing directory name.
     */
    fun parse(root: Path, skillFile: Path, rootName: String): Skill {
        val directory = skillFile.parent
        val path = directory?.invariantSeparatorsPathString ?: "."
        val fallbackName = directory?.fileName?.toString() ?: rootName
        fun fallback(warning: String) = Skill(fallbackName, "", path, listOf(warning))

        val file = root.resolve(skillFile)
        val bytes = try {
            if (Files.size(file) > MAX_FILE_SIZE) return fallback(FILE_TOO_LARGE)
            Files.readAllBytes(file)
        } catch (e: IOException) {
            return fallback(UNREADABLE_FILE)
        }

        val fields = decodeUtf8(bytes)?.let(::extractFrontmatter)?.let(::parseYaml)
            ?: return fallback(INVALID_FRONTMATTER)

        val warnings = mutableListOf<String>()
        val name = fields.scalar("name")?.trim()?.ifEmpty { null }
            ?: fallbackName.also { warnings += MISSING_NAME }
        val description = fields.scalar("description")?.trim().orEmpty()
        if (description.isEmpty()) warnings += MISSING_DESCRIPTION
        return Skill(name, description, path, warnings)
    }

    /** Returns the text between the opening `---` line and the next `---` line, or null if there is none. */
    internal fun extractFrontmatter(text: String): String? {
        val lines = text.removePrefix("﻿").lines()
        if (lines.firstOrNull()?.trimEnd() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trimEnd() == "---" }
        if (end < 0) return null
        return lines.subList(1, end + 1).joinToString("\n")
    }

    private fun parseYaml(yaml: String): Map<*, *>? {
        val settings = LoadSettings.builder().setMaxAliasesForCollections(50).build()
        return try {
            when (val document = Load(settings).loadFromString(yaml)) {
                null -> emptyMap<String, Any>()
                is Map<*, *> -> document
                else -> null
            }
        } catch (e: YamlEngineException) {
            null
        }
    }

    private fun Map<*, *>.scalar(key: String): String? = when (val value = this[key]) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

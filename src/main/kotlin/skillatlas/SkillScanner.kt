package skillatlas

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString

/** Finds skill files in a checked-out repository (spec section 4.2). */
object SkillScanner {
    const val SKILL_FILE_NAME = "SKILL.md"
    val SKIPPED_DIRECTORIES = setOf(".git", "node_modules", "vendor", "dist", "build")

    /**
     * Returns the path of every skill file under [root], relative to [root] and sorted.
     * Symbolic links are never followed, so nothing outside [root] is visited.
     */
    fun discover(root: Path): List<Path> {
        val start = root.toRealPath()
        val found = mutableListOf<Path>()
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != start && dir.fileName.toString() in SKIPPED_DIRECTORIES) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Symlinks are reported with their own attributes, which are never "regular file".
                if (attrs.isRegularFile && file.fileName.toString() == SKILL_FILE_NAME) {
                    found.add(start.relativize(file))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return found.sortedBy { it.invariantSeparatorsPathString }
    }
}

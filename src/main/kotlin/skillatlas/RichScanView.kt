package skillatlas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.StaticEffect
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** A live status line while scanning, then the highlighted report, rendered with Mosaic (spec sections 5.1 and 5.7). */
class RichScanView : ScanView {
    override fun show(scan: (progress: (String) -> Unit) -> Presentation): Presentation {
        var outcome: Result<Presentation>? = null
        try {
            runMosaicBlocking(onNonInteractive = NonInteractivePolicy.Ignore) {
                var status by remember { mutableStateOf("Starting") }
                var finished by remember { mutableStateOf<Result<Presentation>?>(null) }

                LaunchedEffect(Unit) {
                    // runInterruptible lets Ctrl-C (which cancels the composition) interrupt the blocking scan.
                    val attempt = runCatching {
                        runInterruptible(Dispatchers.IO) { scan { status = it.removeSuffix("...") } }
                    }
                    currentCoroutineContext().ensureActive()
                    outcome = attempt
                    finished = attempt
                }

                when (val result = finished?.getOrNull()) {
                    null -> if (finished == null) StatusLine(status)
                    else -> StaticEffect { Report(result, LocalTerminalState.current.size.columns) }
                }
            }
        } catch (_: CancellationException) {
            // Treated the same as an interrupted scan below.
        }
        return outcome?.getOrThrow() ?: throw ScanInterruptedException()
    }
}

internal val SPINNER = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

@Composable
internal fun StatusLine(status: String) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            frame = (frame + 1) % SPINNER.size
        }
    }
    Row {
        Text("${SPINNER[frame]} ", color = Color.Cyan, textStyle = TextStyle.Bold)
        Text("${sanitize(status)}…", textStyle = TextStyle.Dim)
    }
}

private const val LABEL_WIDTH = 13

/** The finished report. [columns] is the terminal width, used to fit skill descriptions. */
@Composable
internal fun Report(result: ScanResult, columns: Int) = Report(Presentation.SkillList(result), columns)

@Composable
internal fun Report(presentation: Presentation, columns: Int) {
    when (presentation) {
        // Star changes and the star list are short answers, without the title (spec section 5.11).
        is Presentation.StarChanged -> StarChangedLine(presentation)
        is Presentation.StarList -> StarListReport(presentation)
        else -> ScanReport(presentation, columns)
    }
}

/** The title, then the scanned repositories and their skills. */
@Composable
private fun ScanReport(presentation: Presentation, columns: Int) {
    // One column of padding plus a two-column skill indent, and one spare so lines never wrap.
    // Terminals that report no size get the full limit.
    val descriptionLength =
        if (columns <= 0) MAX_DESCRIPTION_LENGTH else (columns - 5).coerceIn(20, MAX_DESCRIPTION_LENGTH)

    Column(modifier = Modifier.padding(horizontal = 1)) {
        Text(" SKILL ATLAS ", textStyle = TextStyle.Bold + TextStyle.Invert)
        Text("")

        when (presentation) {
            is Presentation.SkillList -> {
                RepositoryFields(presentation.result)
                SkillListReport(presentation, descriptionLength)
            }
            is Presentation.SkillDetail -> {
                RepositoryFields(presentation.result)
                SkillDetailReport(presentation)
            }
            is Presentation.MultiList -> {
                presentation.lists.forEachIndexed { i, list ->
                    if (i > 0) {
                        Text("")
                        Text(REPOSITORY_SEPARATOR.take(if (columns > 2) minOf(80, columns - 2) else 80), textStyle = TextStyle.Dim)
                        Text("")
                    }
                    RepositoryFields(list.result)
                    SkillListReport(list, descriptionLength)
                }
                if (presentation.lists.isNotEmpty()) Text("")
                for (owner in presentation.owners) {
                    Row {
                        Text("Searched ")
                        Text(sanitize(owner.label), color = Color.Cyan, textStyle = TextStyle.Bold)
                        Text(owner.counts)
                    }
                }
                Text(presentation.summary, color = Color.Green, textStyle = TextStyle.Bold)
            }
            // Shown by Report without the title.
            is Presentation.StarChanged, is Presentation.StarList -> Unit
        }
    }
}

/** `★ Starred <name> (<id>).` and the other outcomes of `star` and `unstar` (spec section 5.11). */
@Composable
internal fun StarChangedLine(change: Presentation.StarChanged) {
    Row(modifier = Modifier.padding(horizontal = 1)) {
        if (change.starred) Text("★ ", color = Color.Yellow, textStyle = TextStyle.Bold) else Text("☆ ", textStyle = TextStyle.Dim)
        Text(change.before)
        Text(sanitize(change.skill.name), color = Color.Cyan, textStyle = TextStyle.Bold)
        Text(change.after)
        Text(sanitize(change.id), textStyle = TextStyle.Dim)
        Text(").")
    }
}

/** The starred skills: a count line, then one row per star (spec section 5.11). */
@Composable
internal fun StarListReport(list: Presentation.StarList) {
    Column(modifier = Modifier.padding(horizontal = 1)) {
        if (list.stars.isEmpty()) {
            Text(list.heading, color = Color.Yellow)
        } else {
            Text(list.heading, color = Color.Green, textStyle = TextStyle.Bold)
            Text("")
            val width = list.stars.maxOf { sanitize(it.name).length }
            for (star in list.stars) {
                Row {
                    Text("★ ", color = Color.Yellow, textStyle = TextStyle.Bold)
                    Text(sanitize(star.name).padEnd(width), color = Color.Cyan, textStyle = TextStyle.Bold)
                    Text("  ")
                    Text(sanitize(star.id), textStyle = TextStyle.Dim)
                }
            }
        }
    }
}

/** The repository, description and commit fields, without the title, for reports that show several repositories. */
@Composable
private fun RepositoryFields(result: ScanResult) {
    val repository = result.repository
    Field("Repository") {
        Text(sanitize(repository.fullName), color = Color.Cyan, textStyle = TextStyle.Bold)
    }
    Field("Description") {
        val description = repository.description?.let(::shortenDescription)?.ifBlank { null }
        if (description == null) {
            Text("(none)", textStyle = TextStyle.Dim)
        } else {
            Text(description, textStyle = TextStyle.Italic)
        }
    }
    Field("Commit") {
        Text(result.commit, color = Color.Yellow, textStyle = TextStyle.Bold)
        Text("  ")
        Text(sanitize(result.branch), color = Color.Magenta)
    }
    Text("")
}

/** The title and the repository, description and commit fields that start every report. */
@Composable
internal fun RepositoryHeader(result: ScanResult) {
    val repository = result.repository
    Text(" SKILL ATLAS ", textStyle = TextStyle.Bold + TextStyle.Invert)
    Text("")

    Field("Repository") {
        Text(sanitize(repository.fullName), color = Color.Cyan, textStyle = TextStyle.Bold)
    }
    Field("Description") {
        val description = repository.description?.let(::shortenDescription)?.ifBlank { null }
        if (description == null) {
            Text("(none)", textStyle = TextStyle.Dim)
        } else {
            Text(description, textStyle = TextStyle.Italic)
        }
    }
    Field("Commit") {
        Text(result.commit, color = Color.Yellow, textStyle = TextStyle.Bold)
        Text("  ")
        Text(sanitize(result.branch), color = Color.Magenta)
    }
}

/** `<n> skills found`, or `No skills found.` */
@Composable
internal fun SkillCount(total: Int) {
    if (total == 0) {
        Text("No skills found.", color = Color.Yellow)
    } else {
        Text("$total ${if (total == 1) "skill" else "skills"} found", color = Color.Green, textStyle = TextStyle.Bold)
    }
}

@Composable
private fun SkillListReport(list: Presentation.SkillList, descriptionLength: Int) {
    val result = list.result
    val words = list.words
    val total = result.skills.size
    val skills = list.skills
    val query = sanitize(list.query.orEmpty())
    when {
        total == 0 || words == null -> SkillCount(total)
        skills.isEmpty() -> Text("No skills match \"$query\".", color = Color.Yellow)
        else -> Text("${skills.size} of $total skills match \"$query\"", color = Color.Green, textStyle = TextStyle.Bold)
    }
    for (skill in skills) {
        Text("")
        SkillEntry(skill, descriptionLength, words.orEmpty())
    }
    if (result.ignored.isNotEmpty()) {
        Text("")
        Text(ignoredHeading(result.ignored.size), textStyle = TextStyle.Dim)
        for (ignored in result.ignored) {
            Text("  ${sanitize(ignored.path)}", textStyle = TextStyle.Dim)
        }
    }
}

@Composable
private fun SkillDetailReport(detail: Presentation.SkillDetail) {
    val skill = detail.skill
    Field("Skill") {
        Text(sanitize(skill.name), color = Color.Cyan, textStyle = TextStyle.Bold)
        Tags(skill)
    }
    Field("Path") { Text(sanitize(skill.path), textStyle = TextStyle.Dim) }
    skill.alsoAt.forEachIndexed { i, copy ->
        Field(if (i == 0) "Also in" else "") { Text(sanitize(copy), textStyle = TextStyle.Dim) }
    }
    Field("GitHub") { Text(detail.githubUrl) }
    Text("")

    Text("Description", textStyle = TextStyle.Dim)
    val description = sanitize(skill.description).trim()
    if (description.isEmpty()) {
        Text("  (no description)", textStyle = TextStyle.Dim)
    } else {
        for (line in description.lines()) Text(if (line.isEmpty()) "" else "  $line")
    }
    Text("")

    Text("Similar skills", textStyle = TextStyle.Dim)
    SimilarRows(detail.similar)
    Text("")

    Text("SKILL.md", textStyle = TextStyle.Dim)
    val content = detail.content?.let(::sanitize)?.trimEnd('\n')
    if (content == null) {
        Text("  (content not available)", textStyle = TextStyle.Dim)
    } else {
        for (line in content.lines()) Text(if (line.isEmpty()) "" else "  $line")
    }
}

/** The similar-skills rows of section 5.7: name, bar, score and path, or `No similar skills found.` */
@Composable
internal fun SimilarRows(similar: List<SimilarSkill>) {
    if (similar.isEmpty()) Text("  No similar skills found.", textStyle = TextStyle.Dim)
    val width = similar.maxOfOrNull { it.name.length } ?: 0
    for (row in similar) {
        Row {
            Text("  ${sanitize(row.name).padEnd(width)}  ", textStyle = TextStyle.Bold)
            Text(similarityBar(row.score), color = Color.Cyan)
            Text(" ${"%3d %%".format(row.score)}  ", textStyle = TextStyle.Bold)
            Text(sanitize(row.path), textStyle = TextStyle.Dim)
        }
    }
}

/** [text] with the parts that match [words] shown black on yellow (spec section 5.7). */
@Composable
internal fun Highlighted(text: String, words: List<String>, color: Color = Color.Unspecified, textStyle: TextStyle = TextStyle.Unspecified) {
    Row {
        var at = 0
        for (range in SkillFilter.matchRanges(text, words)) {
            if (range.first > at) Text(text.substring(at, range.first), color = color, textStyle = textStyle)
            Text(text.substring(range.first, range.last + 1), color = Color.Black, background = Color.Yellow, textStyle = textStyle)
            at = range.last + 1
        }
        if (at < text.length || text.isEmpty()) Text(text.substring(at), color = color, textStyle = textStyle)
    }
}

@Composable
private fun Tags(skill: Skill) {
    if (skill.starred) {
        Text("  ★ $STARRED_LABEL", color = Color.Yellow, textStyle = TextStyle.Bold)
    }
    if (skill.shipped) {
        Text("  ◆ $SHIPPED_LABEL", color = Color.Magenta)
    }
    if (skill.warnings.isNotEmpty()) {
        Text("  ⚠ ${skill.warnings.joinToString(", ")}", color = Color.Yellow)
    }
}

@Composable
internal fun Field(label: String, value: @Composable () -> Unit) {
    Row {
        Text(label.padEnd(LABEL_WIDTH), textStyle = TextStyle.Dim)
        value()
    }
}

@Composable
private fun SkillEntry(skill: Skill, descriptionLength: Int, words: List<String>) {
    Row {
        Text("● ", color = Color.Cyan, textStyle = TextStyle.Bold)
        Highlighted(sanitize(skill.name), words, Color.Cyan, TextStyle.Bold)
        Tags(skill)
    }
    val description = if (words.isEmpty()) {
        shortenDescription(skill.description, descriptionLength)
    } else {
        SkillFilter.descriptionLine(skill, words, descriptionLength).orEmpty()
    }
    if (description.isEmpty()) {
        Text("  (no description)", textStyle = TextStyle.Dim)
    } else {
        Row {
            Text("  ")
            Highlighted(description, words)
        }
    }
    Text("  ${sanitize(skill.path)}", textStyle = TextStyle.Dim)
    for (copy in skill.alsoAt) {
        Text("  also in ${sanitize(copy)}", textStyle = TextStyle.Dim)
    }
}

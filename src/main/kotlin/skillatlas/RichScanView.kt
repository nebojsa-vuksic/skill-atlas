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

/** A live status line while scanning, then the highlighted report, rendered with Mosaic (spec section 5.1). */
class RichScanView : ScanView {
    override fun show(scan: (progress: (String) -> Unit) -> ScanResult): ScanResult {
        var outcome: Result<ScanResult>? = null
        try {
            runMosaicBlocking(onNonInteractive = NonInteractivePolicy.Ignore) {
                var status by remember { mutableStateOf("Starting") }
                var finished by remember { mutableStateOf<Result<ScanResult>?>(null) }

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

private val SPINNER = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

@Composable
private fun StatusLine(status: String) {
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
internal fun Report(result: ScanResult, columns: Int) {
    val repository = result.repository
    // One column of padding plus a two-column skill indent, and one spare so lines never wrap.
    // Terminals that report no size get the full limit.
    val descriptionLength =
        if (columns <= 0) MAX_DESCRIPTION_LENGTH else (columns - 5).coerceIn(20, MAX_DESCRIPTION_LENGTH)

    Column(modifier = Modifier.padding(horizontal = 1)) {
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
        Text("")

        val skills = result.skills
        if (skills.isEmpty()) {
            Text("No skills found.", color = Color.Yellow)
        } else {
            val count = "${skills.size} ${if (skills.size == 1) "skill" else "skills"} found"
            Text(count, color = Color.Green, textStyle = TextStyle.Bold)
            for (skill in skills) {
                Text("")
                SkillEntry(skill, descriptionLength)
            }
        }
        if (result.ignored.isNotEmpty()) {
            Text("")
            Text(ignoredHeading(result.ignored.size), textStyle = TextStyle.Dim)
            for (ignored in result.ignored) {
                Text("  ${sanitize(ignored.path)}", textStyle = TextStyle.Dim)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: @Composable () -> Unit) {
    Row {
        Text(label.padEnd(LABEL_WIDTH), textStyle = TextStyle.Dim)
        value()
    }
}

@Composable
private fun SkillEntry(skill: Skill, descriptionLength: Int) {
    Row {
        Text("● ${sanitize(skill.name)}", color = Color.Cyan, textStyle = TextStyle.Bold)
        if (skill.shipped) {
            Text("  ◆ $SHIPPED_LABEL", color = Color.Magenta)
        }
        if (skill.warnings.isNotEmpty()) {
            Text("  ⚠ ${skill.warnings.joinToString(", ")}", color = Color.Yellow)
        }
    }
    val description = shortenDescription(skill.description, descriptionLength)
    if (description.isEmpty()) {
        Text("  (no description)", textStyle = TextStyle.Dim)
    } else {
        Text("  $description")
    }
    Text("  ${sanitize(skill.path)}", textStyle = TextStyle.Dim)
    for (copy in skill.alsoAt) {
        Text("  also in ${sanitize(copy)}", textStyle = TextStyle.Dim)
    }
}

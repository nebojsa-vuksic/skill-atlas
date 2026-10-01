package skillatlas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.layout.onKeyEvent
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible

/**
 * `skill-atlas browse`: the scan's status line, then the interactive split view until the
 * user quits (spec section 5.8). [onScanned] runs once the scan has succeeded, and `s`
 * changes [stars] (spec section 5.11).
 */
class BrowseView(private val stars: StarStore, private val onScanned: (ScanResult) -> Unit) {
    fun run(scan: (progress: (String) -> Unit) -> ScanResult) {
        var outcome: Result<ScanResult>? = null
        try {
            runMosaicBlocking(onNonInteractive = NonInteractivePolicy.Ignore) {
                var status by remember { mutableStateOf("Starting") }
                var state by remember { mutableStateOf<BrowseState?>(null) }
                var finished by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val attempt = runCatching {
                        runInterruptible(Dispatchers.IO) {
                            val result = scan { status = it.removeSuffix("...") }
                            var warning: Span? = null
                            val starred = result.withStars(stars.readOrWarn { warning = Span(it, Look.WARNING) })
                            Triple(starred, SkillSimilarity.compute(result.skills, result.contents), warning)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    outcome = attempt.map { it.first }
                    attempt.onSuccess { (result, similar, warning) ->
                        onScanned(result)
                        state = BrowseState(result, similar, stars, warning)
                    }
                    finished = true
                }

                val browsing = state
                when {
                    !finished -> StatusLine(status)
                    browsing != null && !browsing.quit -> Browser(browsing)
                }
            }
        } catch (_: CancellationException) {
            // Treated the same as an interrupted scan below.
        }
        outcome?.getOrThrow() ?: throw ScanInterruptedException()
    }
}

@Composable
internal fun Browser(state: BrowseState) {
    // Keeps the composition, and so the program, alive until the user quits.
    LaunchedEffect(state) { snapshotFlow { state.quit }.first { it } }

    val size = LocalTerminalState.current.size
    // One row fewer than the terminal, so drawing the last line never scrolls the screen.
    val frame = BrowseScreen.render(state, size.columns, size.rows - 1)
    Column(
        modifier = Modifier.onKeyEvent { event ->
            state.onKey(event.key, event.ctrl, event.alt, frame.page, frame.maxScroll)
        },
    ) {
        for (line in frame.lines) {
            Row {
                if (line.isEmpty()) Text(" ")
                for (span in line) LookText(span)
            }
        }
    }
}

@Composable
internal fun LookText(span: Span) {
    when (span.look) {
        Look.NORMAL -> Text(span.text)
        Look.DIM -> Text(span.text, textStyle = TextStyle.Dim)
        Look.BOLD -> Text(span.text, textStyle = TextStyle.Bold)
        Look.ACCENT -> Text(span.text, color = Color.Cyan)
        Look.SELECTED -> Text(span.text, color = Color.Cyan, textStyle = TextStyle.Bold)
        Look.HIGHLIGHT -> Text(span.text, color = Color.Black, background = Color.Yellow)
        Look.TITLE -> Text(span.text, textStyle = TextStyle.Bold + TextStyle.Invert)
        Look.REPOSITORY -> Text(span.text, color = Color.Cyan, textStyle = TextStyle.Bold)
        Look.COMMIT -> Text(span.text, color = Color.Yellow)
        Look.BRANCH -> Text(span.text, color = Color.Magenta)
        Look.SHIPPED -> Text(span.text, color = Color.Magenta)
        Look.WARNING -> Text(span.text, color = Color.Yellow)
        Look.COUNT -> Text(span.text, color = Color.Cyan, textStyle = TextStyle.Bold)
        Look.BAR -> Text(span.text, color = Color.Cyan)
        Look.CURSOR -> Text(span.text, textStyle = TextStyle.Invert)
        Look.ERROR -> Text(span.text, color = Color.Red)
        Look.STAR -> Text(span.text, color = Color.Yellow, textStyle = TextStyle.Bold)
    }
}

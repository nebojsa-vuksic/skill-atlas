package skillatlas

/** How a piece of `browse` or `shell` text is drawn; the Mosaic layer maps each to colors (spec sections 5.8 and 5.9). */
enum class Look { NORMAL, DIM, BOLD, ACCENT, SELECTED, HIGHLIGHT, TITLE, REPOSITORY, COMMIT, BRANCH, SHIPPED, WARNING, COUNT, BAR, CURSOR, ERROR, STAR }

data class Span(val text: String, val look: Look = Look.NORMAL)

typealias Line = List<Span>

val Line.text: String get() = joinToString("") { it.text }

/** One rendered frame, plus what paging needs to know about the right pane. */
data class Frame(val lines: List<Line>, val page: Int, val maxScroll: Int)

/** Lays out `browse` as styled lines at a given terminal size (spec section 5.8). Pure, so it can be tested. */
object BrowseScreen {
    const val MIN_COLUMNS = 60
    const val MIN_ROWS = 10
    private const val MIN_LEFT = 28
    private const val MIN_RIGHT = 40
    private const val MAX_DESCRIPTION_LINES = 6

    private val HELP = mapOf(
        BrowseFocus.LIST to "↑↓ select  / filter  s star  tab similar  pgup/pgdn scroll  esc clear  q quit",
        BrowseFocus.FILTER to "type to filter  enter/↓ done  esc clear  backspace delete",
        BrowseFocus.SIMILAR to "↑↓ move  enter open  tab/esc back",
    )

    fun render(state: BrowseState, columns: Int, rows: Int): Frame {
        if (columns < MIN_COLUMNS || rows < MIN_ROWS) {
            return Frame(listOf(listOf(Span("Terminal too small: browse needs at least ${MIN_COLUMNS}×$MIN_ROWS.", Look.WARNING))), 1, 0)
        }
        val body = rows - 2
        val left = (columns * 0.4).toInt().coerceIn(MIN_LEFT, columns - MIN_RIGHT)
        val right = columns - left - 1

        val rightLines = rightPane(state, right - 1)
        val maxScroll = maxOf(0, rightLines.size - body)
        val shown = rightLines.drop(minOf(state.scroll, maxScroll))

        val leftLines = leftPane(state, left, body)
        val lines = buildList {
            add(fit(topLine(state.result), columns))
            for (i in 0 until body) {
                val rightLine = shown.getOrNull(i).orEmpty()
                add(fit(leftLines[i], left) + Span("│", Look.DIM) + fit(listOf(Span(" ")) + rightLine, right))
            }
            val notice = state.notice
            add(fit(listOf(if (notice == null) Span(" " + HELP.getValue(state.focus), Look.DIM) else notice.copy(text = " " + notice.text)), columns))
        }
        return Frame(lines, body, maxScroll)
    }

    private fun topLine(result: ScanResult): Line = listOf(
        Span(" SKILL ATLAS ", Look.TITLE),
        Span("  "),
        Span(sanitize(result.repository.fullName), Look.REPOSITORY),
        Span("  "),
        Span(result.commit.take(12), Look.COMMIT),
        Span(" "),
        Span(sanitize(result.branch), Look.BRANCH),
    )

    // ---- Left pane: filter line, rule, and the skills ----

    private fun leftPane(state: BrowseState, width: Int, height: Int): List<Line> {
        val skills = state.visible
        val total = state.result.skills.size
        val count = "${skills.size} of $total "
        val filter = when {
            state.focus == BrowseFocus.FILTER -> listOf(Span(" / "), Span(sanitize(state.query), Look.BOLD), Span("▏", Look.ACCENT))
            state.query.isEmpty() -> listOf(Span(" / filter", Look.DIM))
            else -> listOf(Span(" / "), Span(sanitize(state.query), Look.BOLD))
        }
        val lines = mutableListOf(
            fit(filter, width - count.length) + Span(count, if (state.query.isBlank()) Look.DIM else Look.COUNT),
            listOf(Span("─".repeat(width), Look.DIM)),
        )

        val region = height - 2
        if (skills.isEmpty() && total > 0) {
            lines.add(listOf(Span(" No skills match \"${sanitize(state.query)}\".", Look.WARNING)))
        } else {
            // Each skill takes three lines: name, description, blank. The selection is kept in view.
            val fits = maxOf(1, region / 3)
            val selected = skills.indexOfFirst { it.path == state.selectedPath }.coerceAtLeast(0)
            val top = if (selected < fits) 0 else selected - fits + 1
            for (skill in skills.drop(top).take(fits)) {
                lines.addAll(skillItem(skill, skill.path == state.selectedPath, state.words, width))
            }
        }
        while (lines.size < height) lines.add(emptyList<Span>())
        return lines.take(height)
    }

    private fun skillItem(skill: Skill, selected: Boolean, words: List<String>, width: Int): List<Line> {
        val bar = if (selected) Span("▌", Look.ACCENT) else Span(" ")
        val icons = buildList {
            if (skill.starred) add(Span("★", Look.STAR))
            if (skill.shipped) add(Span("◆", Look.SHIPPED))
            if (skill.warnings.isNotEmpty()) add(Span("⚠", Look.WARNING))
            if (skill.alsoAt.isNotEmpty()) add(Span("⧉${skill.alsoAt.size}", Look.DIM))
        }
        val iconSpans = icons.flatMapIndexed { i, icon -> if (i == 0) listOf(icon) else listOf(Span(" "), icon) } + Span(" ")
        val iconWidth = iconSpans.sumOf { it.text.length }
        val nameLook = if (selected) Look.SELECTED else Look.ACCENT
        val name = fit(highlighted(sanitize(skill.name), words, nameLook), width - 1 - iconWidth - 1)
        val nameLine = listOf(bar) + pad(name, width - 1 - iconWidth) + iconSpans

        val description = SkillFilter.descriptionLine(skill, words, width - 2)
        val descriptionLine = listOf(bar) + fit(
            if (description == null) listOf(Span("(no description)", Look.DIM)) else highlighted(description, words, Look.DIM),
            width - 2,
        )
        return listOf(nameLine, descriptionLine, emptyList())
    }

    // ---- Right pane: the selected skill ----

    private fun rightPane(state: BrowseState, width: Int): List<Line> {
        val skill = state.selected ?: return emptyList()
        val lines = mutableListOf<Line>()
        lines.add(buildList {
            add(Span(sanitize(skill.name), Look.SELECTED))
            if (skill.starred) add(Span("  ★ $STARRED_LABEL", Look.STAR))
            if (skill.shipped) add(Span("  ◆ $SHIPPED_LABEL", Look.SHIPPED))
            if (skill.warnings.isNotEmpty()) add(Span("  ⚠ ${skill.warnings.joinToString(", ")}", Look.WARNING))
        })

        val description = wrap(sanitize(skill.description).replace(Regex("\\s+"), " ").trim(), width)
        if (description.isEmpty()) {
            lines.add(listOf(Span("(no description)", Look.DIM)))
        } else {
            val kept = description.take(MAX_DESCRIPTION_LINES).toMutableList()
            if (description.size > MAX_DESCRIPTION_LINES) kept[kept.lastIndex] = kept.last().take(width - 1).trimEnd() + "…"
            kept.forEach { lines.add(listOf(Span(it))) }
        }
        lines.add(listOf(Span(sanitize(skill.path), Look.DIM)))
        skill.alsoAt.forEach { lines.add(listOf(Span("also in ${sanitize(it)}", Look.DIM))) }
        lines.add(emptyList<Span>())

        lines.add(listOf(Span("Similar skills", Look.BOLD)))
        val similar = state.similar
        if (similar.isEmpty()) lines.add(listOf(Span("No similar skills found.", Look.DIM)))
        val nameWidth = similar.maxOfOrNull { it.name.length } ?: 0
        similar.forEachIndexed { i, row ->
            val cursor = state.focus == BrowseFocus.SIMILAR && i == state.similarCursor
            lines.add(
                listOf(
                    Span(if (cursor) "▸ " else "  ", Look.ACCENT),
                    Span(sanitize(row.name).padEnd(nameWidth), if (cursor) Look.SELECTED else Look.NORMAL),
                    Span("  "),
                    Span(similarityBar(row.score), Look.BAR),
                    Span(" %3d %%".format(row.score), Look.BOLD),
                ),
            )
        }
        lines.add(emptyList<Span>())

        lines.add(listOf(Span("SKILL.md", Look.BOLD)))
        val content = state.result.contents[skill.path]
        if (content == null) {
            lines.add(listOf(Span("(content not available)", Look.DIM)))
        } else {
            for (line in sanitize(content).trimEnd('\n').lines()) {
                // Continuation lines keep the line's indentation, so YAML and nested lists stay readable.
                val expanded = line.replace("\t", "    ")
                val indent = " ".repeat(expanded.takeWhile { it == ' ' }.length.coerceAtMost(width / 2))
                val wrapped = wrap(expanded.trimStart(), width - indent.length)
                if (wrapped.isEmpty()) lines.add(emptyList()) else wrapped.forEach { lines.add(listOf(Span(indent + it))) }
            }
        }
        return lines
    }

    // ---- Text helpers ----

    /** [text] split into spans, with the parts matching [words] in [Look.HIGHLIGHT]. */
    fun highlighted(text: String, words: List<String>, look: Look): Line {
        val spans = mutableListOf<Span>()
        var at = 0
        for (range in SkillFilter.matchRanges(text, words)) {
            if (range.first > at) spans += Span(text.substring(at, range.first), look)
            spans += Span(text.substring(range.first, range.last + 1), Look.HIGHLIGHT)
            at = range.last + 1
        }
        if (at < text.length) spans += Span(text.substring(at), look)
        return spans
    }

    /** [line] cut to [width] columns, ending in `…` when something was cut, then padded with spaces. */
    fun fit(line: Line, width: Int): Line {
        if (width <= 0) return emptyList()
        val length = line.sumOf { it.text.length }
        if (length <= width) return pad(line, width)
        val result = mutableListOf<Span>()
        var room = width - 1
        for (span in line) {
            if (room <= 0) break
            val part = span.text.take(room)
            result += span.copy(text = part)
            room -= part.length
        }
        result += Span("…", line.lastOrNull { it.text.isNotEmpty() }?.look ?: Look.NORMAL)
        return result
    }

    private fun pad(line: Line, width: Int): Line {
        val length = line.sumOf { it.text.length }
        return if (length >= width) line else line + Span(" ".repeat(width - length))
    }

    /** [text] word-wrapped to [width] columns; words longer than a line are cut. */
    fun wrap(text: String, width: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            var rest = word
            while (rest.length > width) {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder()
                }
                lines.add(rest.take(width))
                rest = rest.drop(width)
            }
            if (current.isEmpty()) {
                current.append(rest)
            } else if (current.length + 1 + rest.length <= width) {
                current.append(' ').append(rest)
            } else {
                lines.add(current.toString())
                current = StringBuilder(rest)
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) lines.add(current.toString())
        return lines
    }
}

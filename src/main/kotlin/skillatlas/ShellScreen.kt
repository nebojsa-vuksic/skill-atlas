package skillatlas

/** Lays out the shell's live area, the prompt and the palette, as styled lines (spec section 5.9). Pure, so it can be tested. */
object ShellScreen {
    const val PROMPT = "❯ "
    const val HINT = "type / for commands"
    const val PALETTE_ROWS = 8

    fun render(state: ShellState, columns: Int): List<Line> {
        // One spare column, so a full line never makes the terminal wrap.
        val width = if (columns <= 0) Int.MAX_VALUE else maxOf(columns - 1, PROMPT.length + 2)
        val lines = mutableListOf(promptLine(state, width))
        val items = state.palette
        if (items.isNotEmpty()) lines.addAll(palette(items, state.selection, width))
        return lines
    }

    private fun promptLine(state: ShellState, width: Int): Line {
        val prompt = Span(PROMPT, Look.SELECTED)
        val input = state.input
        if (input.isEmpty()) {
            return BrowseScreen.fit(listOf(prompt, Span(" ", Look.CURSOR), Span(HINT, Look.DIM)), width).dropLastWhile { it.look == Look.NORMAL }
        }

        // The cursor takes a cell of its own at the end of the input. Scroll sideways to keep it visible.
        val room = width - PROMPT.length
        val cells = input + " "
        var start = 0
        if (cells.length > room) start = minOf(maxOf(0, state.cursor - room + 2), cells.length - room + 1)
        val shown = cells.substring(start).take(if (start > 0) room - 1 else room)
        val at = state.cursor - start
        return buildList {
            add(prompt)
            if (start > 0) add(Span("…", Look.DIM))
            if (at > 0) add(Span(shown.substring(0, at)))
            add(Span(shown.substring(at, at + 1), Look.CURSOR))
            if (at + 1 < shown.length) add(Span(shown.substring(at + 1)))
        }
    }

    /** The window of at most [PALETTE_ROWS] rows that keeps [selection] in view. */
    fun window(size: Int, selection: Int): IntRange {
        val top = if (selection < PALETTE_ROWS) 0 else selection - PALETTE_ROWS + 1
        return top until minOf(size, top + PALETTE_ROWS)
    }

    private fun palette(items: List<PaletteItem>, selection: Int, width: Int): List<Line> {
        val labels = items.map { label(it) }
        val labelWidth = labels.maxOf { it.length }
        return window(items.size, selection).map { i ->
            val item = items[i]
            val selected = i == selection
            val nameLook = if (selected) Look.SELECTED else Look.ACCENT
            val label = labels[i]
            // The match is inside the name, which starts after the command's `/` or at the skill argument.
            val offset = if (item is PaletteItem.Command) 1 else 0
            val range = item.match.range?.let { it.first + offset..it.last + offset }
            val labelSpans = buildList {
                if (range == null) {
                    add(Span(label, nameLook))
                } else {
                    if (range.first > 0) add(Span(label.substring(0, range.first), nameLook))
                    add(Span(label.substring(range.first, range.last + 1), Look.HIGHLIGHT))
                    if (range.last + 1 < label.length) add(Span(label.substring(range.last + 1), nameLook))
                }
            }
            val line = listOf(Span(if (selected) "▸ " else "  ", Look.ACCENT)) + labelSpans +
                Span(" ".repeat(labelWidth - label.length + 2)) + Span(sanitize(item.description), Look.DIM)
            BrowseScreen.fit(line, width).dropLastWhile { it.text.isBlank() }
        }
    }

    private fun label(item: PaletteItem) = when (item) {
        is PaletteItem.Command -> item.command.label
        is PaletteItem.SkillArgument -> item.match.text
    }
}

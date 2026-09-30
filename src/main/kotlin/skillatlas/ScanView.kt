package skillatlas

import java.io.PrintStream

/** How a scan is presented (spec section 5). */
fun interface ScanView {
    /**
     * Runs [scan], showing its progress while it works and its report once it finishes.
     * Returns the result, or rethrows whatever [scan] threw.
     */
    fun show(scan: (progress: (String) -> Unit) -> Presentation): Presentation
}

/** Progress to stderr, then the plain text report to stdout (spec sections 5.2 and 5.7). */
class PlainScanView(
    private val out: PrintStream,
    private val err: PrintStream,
) : ScanView {
    override fun show(scan: (progress: (String) -> Unit) -> Presentation): Presentation {
        val presentation = scan(err::println)
        out.print(TextReport.render(presentation))
        out.flush()
        return presentation
    }
}

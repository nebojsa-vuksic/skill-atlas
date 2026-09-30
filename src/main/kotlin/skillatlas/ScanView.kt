package skillatlas

import java.io.PrintStream

/** How a scan is presented (spec section 5). */
fun interface ScanView {
    /**
     * Runs [scan], showing its progress while it works and its report once it finishes.
     * Returns the result, or rethrows whatever [scan] threw.
     */
    fun show(scan: (progress: (String) -> Unit) -> ScanResult): ScanResult
}

/** Progress to stderr, then the plain text report to stdout (spec section 5.2). */
class PlainScanView(
    private val out: PrintStream,
    private val err: PrintStream,
) : ScanView {
    override fun show(scan: (progress: (String) -> Unit) -> ScanResult): ScanResult {
        val result = scan(err::println)
        out.print(TextReport.render(result))
        out.flush()
        return result
    }
}

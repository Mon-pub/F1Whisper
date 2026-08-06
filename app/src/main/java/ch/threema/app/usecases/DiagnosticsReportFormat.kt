package ch.threema.app.usecases

/**
 * F1Whisper: the shared line format of the connection diagnostics report.
 *
 * Extracted verbatim from [ExportConnectionDiagnosticsUseCase] so [ConnectionDiagnosticsSection] can
 * render with the identical format and, more importantly, the identical **error containment**. The
 * containment is the load-bearing part: the report is a troubleshooting artifact that a user exports
 * while something is already broken, so a single failing probe must degrade to one `n/a` line rather
 * than abort the export and leave us with nothing.
 *
 * Behaviour is unchanged from the private helpers these replace.
 */

/** Hard cap on any single value, so one unexpectedly long probe result cannot bloat the report. */
internal const val MAX_VALUE_CHARS = 512

/**
 * Append a `key:\tvalue` line, capturing the value lazily so any single failing probe degrades to
 * `n/a (...)` instead of aborting the whole report.
 *
 * Catches [Throwable] rather than [Exception] deliberately: a probe that trips an
 * `UninitializedPropertyAccessException`, an `AssertionError` or a `NoClassDefFoundError` on some
 * OEM build must still cost only its own line.
 */
internal fun StringBuilder.kv(key: String, value: () -> Any?) {
    val rendered = try {
        value()?.toString() ?: "null"
    } catch (e: Throwable) {
        "n/a (${e.javaClass.simpleName})"
    }
    val capped = if (rendered.length > MAX_VALUE_CHARS) rendered.take(MAX_VALUE_CHARS) + "…" else rendered
    appendLine("$key:\t$capped")
}

internal fun StringBuilder.section(title: String, block: StringBuilder.() -> Unit) {
    appendLine()
    appendLine("# $title")
    block()
}

package ui

import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.StringSelection
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("ui.CrashReport")

/** Where logback's rolling file appender writes. Defined in `logback.xml`, repeated here once. */
fun logFilePath(): String = "${System.getProperty("user.home")}/.icelens/icelens.log"

/** The version stamped into the jar, or `dev` when running from a build tree. */
fun appVersion(): String = runCatching {
    val props = java.util.Properties()
    Thread.currentThread().contextClassLoader
        .getResourceAsStream("version.properties")
        ?.use { props.load(it) }
        ?: return@runCatching "dev"
    props.getProperty("version", "dev")
}.getOrDefault("dev")

/**
 * The machine, as four lines.
 *
 * Shared by the About dialog's "Copy diagnostic info" and by a crash report, because they are the
 * same four facts and a bug report pasted from one should not be missing a line the other has.
 */
fun diagnosticLines(version: String = appVersion()): String = buildString {
    appendLine("Iceberg Lens $version")
    appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
    appendLine("Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
    appendLine("Runtime: ${System.getProperty("java.runtime.name")} ${System.getProperty("java.runtime.version")}")
}

/**
 * The deepest cause's type and message — what the reader needs before the stack.
 *
 * An exception that crosses a layer is usually wrapped, and the outer message is the wrapper's:
 * "Failed to load table" says nothing that "NoSuchFileException: /wh/db/t/metadata" does not say
 * better. The chain is walked with a guard, because a cycle in `cause` is rare and a hang inside a
 * crash handler is unrecoverable.
 */
fun rootCauseLine(throwable: Throwable): String {
    var current = throwable
    val seen = mutableSetOf<Throwable>()
    while (seen.add(current)) {
        current = current.cause ?: break
    }
    val message = current.message?.takeIf { it.isNotBlank() }
    return if (message != null) "${current::class.java.name}: $message" else current::class.java.name
}

/** How much stack a report carries. Past this the reader is scrolling, not reading. */
const val CRASH_REPORT_STACK_LINES = 200

/**
 * The whole report, ready to paste into a ticket.
 *
 * The stack comes from `printStackTrace`, not from a hand-rolled walk of `cause`: that is what
 * emits `Caused by:` and `Suppressed:` in the form every JVM reader already knows, and
 * reimplementing it produces something subtly less complete. It is capped, and says so where it
 * was cut — an uncapped report can be megabytes after a `StackOverflowError`, which is exactly the
 * crash most likely to produce one, and neither a dialog nor a clipboard survives that well.
 */
fun crashReport(
    throwable: Throwable,
    threadName: String,
    at: Instant,
    version: String = appVersion(),
    logFile: String = logFilePath(),
): String {
    val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        .lines()
        .let { lines ->
            if (lines.size <= CRASH_REPORT_STACK_LINES) lines
            else lines.take(CRASH_REPORT_STACK_LINES) +
                "\t... ${lines.size - CRASH_REPORT_STACK_LINES} more lines, see $logFile"
        }
    return buildString {
        appendLine("Iceberg Lens crashed.")
        appendLine()
        appendLine(rootCauseLine(throwable))
        appendLine()
        appendLine("When: ${DateTimeFormatter.ISO_INSTANT.format(at)} (${at.atZone(ZoneId.systemDefault()).zone})")
        appendLine("Thread: $threadName")
        append(diagnosticLines(version))
        appendLine("Log: $logFile")
        appendLine()
        stack.forEach { appendLine(it) }
    }.trimEnd() + "\n"
}

/**
 * Installs the handler that turns an uncaught exception into a report the reader can send.
 *
 * Two decisions worth stating.
 *
 * **It does not exit.** The state after an uncaught exception is unknown, but a window frozen with
 * a dialog on it is one the reader can copy from, and one that has quit is not. Quitting would
 * destroy the only artifact the crash produced.
 *
 * **It shows one dialog, ever.** A failing composition re-throws every frame, and a handler that
 * opens a window per exception buries the machine under them. The rest are logged; the log has all
 * of them, and the first one is the one that explains the others.
 */
fun installCrashHandler(show: (String) -> Unit = ::showCrashDialog) {
    val shown = AtomicBoolean(false)
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // Nothing in here may throw. A handler that fails loses the exception it was handed and
        // replaces it with its own, which is the least useful outcome available.
        runCatching {
            val report = crashReport(throwable, thread.name, Instant.now())
            logger.error("Uncaught exception on thread {}", thread.name, throwable)
            if (shown.compareAndSet(false, true)) show(report)
        }
    }
}

/**
 * The report in a window, with a button that copies it.
 *
 * Swing rather than Compose: the exception may well have come from composition, and asking a
 * broken Compose runtime to draw the report about its own failure is asking the wrong runtime.
 * Skipped entirely when there is no display, which is what a test run and a headless machine are.
 */
fun showCrashDialog(report: String) {
    if (GraphicsEnvironment.isHeadless()) return
    runCatching {
        javax.swing.SwingUtilities.invokeLater {
            runCatching {
                val area = javax.swing.JTextArea(report, 24, 90).apply {
                    isEditable = false
                    lineWrap = false
                    font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
                }
                val choice = javax.swing.JOptionPane.showOptionDialog(
                    null,
                    javax.swing.JScrollPane(area),
                    "Iceberg Lens crashed",
                    javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.ERROR_MESSAGE,
                    null,
                    arrayOf("Copy error details", "Close"),
                    "Copy error details",
                )
                if (choice == 0) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(report), null)
                }
            }
        }
    }
}

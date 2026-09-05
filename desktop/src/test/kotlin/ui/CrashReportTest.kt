package ui

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a crash report says, and which threads produce one.
 *
 * The report is the whole artifact a crash leaves behind — the window is gone or frozen, so what
 * the reader pastes into an issue is all anyone will ever have. That makes its contents worth
 * asserting rather than eyeballing once.
 */
class CrashReportTest {

    private var previous: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun rememberHandler() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
    }

    @AfterTest
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    private val at = Instant.parse("2026-09-05T10:15:30Z")

    private fun report(t: Throwable) =
        crashReport(t, threadName = "AWT-EventQueue-0", at = at, version = "1.2.3", logFile = "/home/u/.icelens/icelens.log")

    /**
     * The line above the stack names the *deepest* cause.
     *
     * An exception that crosses a layer arrives wrapped, and the wrapper's message is the one that
     * says least: "Failed to load table" against "NoSuchFileException: /wh/db/t/metadata".
     */
    @Test
    fun `the summary line names the root cause, not the wrapper`() {
        val root = java.nio.file.NoSuchFileException("/wh/db/t/metadata/v1.metadata.json")
        val wrapped = IllegalStateException("Failed to load table", RuntimeException("reading metadata", root))
        assertEquals(
            "java.nio.file.NoSuchFileException: /wh/db/t/metadata/v1.metadata.json",
            rootCauseLine(wrapped),
        )
    }

    @Test
    fun `a cause cycle does not hang the handler`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        // The assertion is that this returns at all; which of the two it names is arbitrary.
        assertTrue(rootCauseLine(a).startsWith("java.lang.RuntimeException: "))
    }

    @Test
    fun `a cause with no message is named by its type alone`() {
        assertEquals("java.lang.NullPointerException", rootCauseLine(NullPointerException()))
    }

    @Test
    fun `the report carries the cause, the thread, the machine, the log and the stack`() {
        val text = report(IllegalStateException("boom", java.io.IOException("disk gone")))
        listOf(
            "Iceberg Lens crashed.",
            "java.io.IOException: disk gone",
            "Thread: AWT-EventQueue-0",
            "2026-09-05T10:15:30Z",
            "Iceberg Lens 1.2.3",
            "Log: /home/u/.icelens/icelens.log",
            "java.lang.IllegalStateException: boom",
            "Caused by: java.io.IOException: disk gone",
        ).forEach { assertTrue(it in text, "the report should carry \"$it\"\n\n$text") }
    }

    /**
     * A `StackOverflowError` is the crash most likely to produce a huge stack, and it is also the
     * one where the report has to survive: neither a dialog nor a clipboard handles megabytes.
     */
    @Test
    fun `a huge stack is capped and says where it was cut`() {
        val deep = RuntimeException("deep").apply {
            stackTrace = Array(5_000) { StackTraceElement("C", "m$it", "C.kt", it) }
        }
        val text = report(deep)
        assertTrue(text.lines().size < CRASH_REPORT_STACK_LINES + 40, "the report should be capped")
        assertTrue("more lines, see /home/u/.icelens/icelens.log" in text, "and should say where the rest is")
    }

    /**
     * One dialog, however many exceptions arrive.
     *
     * A failing composition re-throws every frame. A handler that opens a window per exception
     * makes the machine unusable, on top of a crash the reader is already trying to read about.
     */
    @Test
    fun `the handler reports every exception and shows one dialog`() {
        val shown = mutableListOf<String>()
        installCrashHandler(show = { synchronized(shown) { shown += it } })

        val threads = (1..5).map { i ->
            Thread { throw IllegalStateException("crash $i") }.apply { name = "crasher-$i"; start() }
        }
        threads.forEach { it.join(5_000) }

        assertEquals(1, shown.size, "one dialog for five uncaught exceptions")
        assertTrue("Iceberg Lens crashed." in shown.single())
    }

    /**
     * Whether an exception on the AWT event thread reaches the default handler at all.
     *
     * This is the thread the crash most likely comes from, and the JDK has not always routed it
     * here — `EventDispatchThread` used to consult the `sun.awt.exception.handler` property
     * instead. Asserted rather than assumed, because if it stops holding, the handler this file
     * installs is silently covering less than it claims to.
     */
    @Test
    fun `an exception on the AWT event thread reaches the default handler`() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        val seen = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            if (t.message == "from the EDT") seen.countDown()
        }
        javax.swing.SwingUtilities.invokeLater { throw IllegalStateException("from the EDT") }
        assertTrue(seen.await(10, TimeUnit.SECONDS), "the EDT's exception should reach the default handler")
    }
}

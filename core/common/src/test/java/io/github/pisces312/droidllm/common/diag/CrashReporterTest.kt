package io.github.pisces312.droidllm.common.diag

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun `report captures the thread, the exception and a logcat section`() {
        CrashReporter.install(tmp.newFolder())

        val error = IllegalStateException("boom at token 7")
        val file = CrashReporter.report(Thread.currentThread(), error)

        assertNotNull("a report must be written", file)
        val text = file!!.readText()
        assertTrue(text, text.contains("thread: ${Thread.currentThread().name}"))
        assertTrue(text, text.contains("exception: java.lang.IllegalStateException: boom at token 7"))
        assertTrue(text, text.contains("boom at token 7"))
        // Either the live logcat dump or the explicit "off" notice has to show
        // up -- silently dropping the section is the failure mode to avoid.
        assertTrue(
            text,
            text.contains("logcat") || text.contains("诊断记录已关闭"),
        )
    }

    @Test
    fun `only the newest reports are kept`() {
        val dir = tmp.newFolder()
        CrashReporter.install(dir)

        // Pruning orders by lastModified, and several reports can land in the
        // same millisecond, so pin the timestamps explicitly. The base stays
        // well below "now" so the freshly written report always sorts first.
        val written = (0 until 7).map { index ->
            val file = CrashReporter.report(Thread.currentThread(), RuntimeException("boom-$index"))
            assertNotNull("report $index must be written", file)
            file!!.setLastModified(1_000_000L + index * 1_000L)
            file
        }

        assertEquals(
            "the cap keeps the 5 newest and drops the oldest first",
            written.drop(2).map { it.name }.toSet(),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `uncaughtException still hands the crash to the previous handler`() {
        // A previous handler must be installed before CrashReporter chains onto
        // it: with none, the fallback path calls exitProcess, which would take
        // the test JVM down with it.
        val seen = AtomicReference<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> seen.set(throwable) }

        val dir = tmp.newFolder()
        CrashReporter.install(dir)

        val error = RuntimeException("delegated")
        CrashReporter.uncaughtException(Thread.currentThread(), error)

        assertEquals("the crash must not be swallowed", error, seen.get())
        assertTrue("a report must still be written", dir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `listReports is newest first`() {
        val dir = tmp.newFolder()
        CrashReporter.install(dir)

        val older = File(dir, "crash_older.txt").apply { writeText("a"); setLastModified(1_000L) }
        val newer = File(dir, "crash_newer.txt").apply { writeText("b"); setLastModified(2_000L) }

        assertEquals(listOf(newer.name, older.name), CrashReporter.listReports().map { it.name })
    }

    @Test
    fun `install resolves the report directory`() {
        val dir = tmp.newFolder()
        CrashReporter.install(dir)
        assertEquals(dir.absolutePath, CrashReporter.reportDirPath())
    }
}

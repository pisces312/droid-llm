package io.github.pisces312.droidllm.common.diag

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Writes a crash report to `filesDir/crash/` before the process dies.
 *
 * Scope: **Java/Kotlin crashes only.** A native `SIGSEGV` never reaches this
 * code -- nothing runs in the dying process. Two consequences:
 *
 * - Java crash: the report is written at crash time and is on disk next launch.
 * - Native crash: recovered the other way round. Android's own `crash_dump`
 *   writes the tombstone into the **crash logcat buffer**, which survives the
 *   process, so [EngineLogcatCapture] reads it back on the next launch.
 *
 * Upstream MNN's `crash_util.cpp` tried the missing piece (a `sigaction` +
 * `_Unwind_Backtrace` handler) and disabled it again at the call site --
 * `MnnLlmChat`'s `CrashUtil.kt` has `//seems not work fine for native crash`
 * above a commented-out `initNative(...)`. Porting it would mean a new native
 * library plus build wiring for a path upstream abandoned, so this project
 * takes the two paths that are known to work instead.
 *
 * Design constraints, same contract as [DiagLogger]:
 * - **Never throws.** A failure in the handler must not replace the real crash
 *   with a confusing second one.
 * - **Never swallows.** The previous handler is always invoked, so the system
 *   still gets to kill the process and show its own crash dialog.
 * - **Bounded.** At most [MAX_REPORTS] files are kept, oldest pruned first.
 */
object CrashReporter : Thread.UncaughtExceptionHandler {

    private const val TAG = "crash"
    private const val DIR_NAME = "crash"
    private const val MAX_REPORTS = 5

    /** Resolved in [install]; null disables report writing entirely. */
    private var reportDir: File? = null

    private var previous: Thread.UncaughtExceptionHandler? = null

    /**
     * Latch, not a lock to release: after the first report the process is on
     * its way out, and a failure *inside* this handler would otherwise recurse.
     */
    private val reporting = AtomicBoolean(false)

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val humanFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Installs the handler. Safe to call once from `Application.onCreate`. */
    fun install(context: Context) = install(File(context.filesDir, DIR_NAME))

    /**
     * Test seam. [install] only resolves a directory, so the whole handler can
     * be exercised from a JVM unit test against a temporary folder.
     */
    fun install(dir: File) {
        reportDir = dir
        // Guard against chaining onto ourselves when install() runs twice.
        previous = Thread.getDefaultUncaughtExceptionHandler()?.takeIf { it !== this }
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (reporting.compareAndSet(false, true)) {
            runCatching {
                DiagLogger.e(TAG, "uncaught on ${thread.name}: ${error.javaClass.name}: ${error.message}")
            }
            runCatching { report(thread, error) }
        }

        val fallback = previous
        if (fallback != null) {
            fallback.uncaughtException(thread, error)
        } else {
            // No one else to report to: match what the platform would do.
            runCatching { Process.killProcess(Process.myPid()) }
            runCatching { exitProcess(10) }
        }
    }

    /**
     * Writes one report and returns it, or null when storage is unavailable.
     *
     * Callable on its own (that is what the unit tests do) so the rendering can
     * be verified without provoking a real crash.
     */
    fun report(thread: Thread, error: Throwable): File? {
        val target = nextFile() ?: return null
        return runCatching {
            target.writeText(render(thread, error))
            prune()
            target
        }.getOrNull()
    }

    /** Reports newest first. */
    fun listReports(): List<File> =
        runCatching {
            reportDir?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
        }.getOrNull().orEmpty()

    fun latestReport(): File? = listReports().firstOrNull()

    fun deleteAllReports() {
        runCatching { listReports().forEach { it.delete() } }
    }

    /** Directory shown on screen so the user can also pull it with adb. */
    fun reportDirPath(): String? = reportDir?.absolutePath

    private fun render(thread: Thread, error: Throwable): String = buildString {
        appendLine("DroidLLM crash report")
        appendLine("generated: ${humanFormat.format(Date())}")
        appendLine("thread: ${thread.name}")
        appendLine("exception: ${error.javaClass.name}: ${error.message}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("pid: ${runCatching { Process.myPid() }.getOrDefault(-1)}")
        appendLine("=".repeat(48))
        appendLine(error.stackTraceToString())
        appendLine("=".repeat(48))
        if (DiagLogger.isEnabled()) {
            appendLine("logcat (this pid, main+system+crash buffers):")
            appendLine(EngineLogcatCapture.capture() ?: "(logcat 未取到)")
        } else {
            // The buffer can echo prompt text from native logs, so it follows
            // the same switch as the rest of the diagnostics. The stack trace
            // above never contains user content and is always written.
            appendLine("logcat: 诊断记录已关闭，未附带（异常堆栈不受影响）")
        }
    }

    private fun nextFile(): File? {
        val dir = reportDir ?: return null
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return null
            // The stamp is millisecond resolution, which repeats when reports
            // are written back to back; never overwrite one that already exists.
            val base = "crash_${stampFormat.format(Date())}"
            var candidate = File(dir, "$base.txt")
            var suffix = 1
            while (candidate.exists()) {
                candidate = File(dir, "${base}_$suffix.txt")
                suffix++
            }
            candidate
        }.getOrNull()
    }

    private fun prune() {
        val dir = reportDir ?: return
        runCatching {
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_REPORTS)
                ?.forEach { it.delete() }
        }
    }
}

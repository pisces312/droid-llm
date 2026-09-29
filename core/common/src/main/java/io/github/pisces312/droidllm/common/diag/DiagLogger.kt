package io.github.pisces312.droidllm.common.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    /** Single-char tag used in the rendered line, keeps the log narrow. */
    val tag: String
        get() = when (this) {
            DEBUG -> "D"
            INFO -> "I"
            WARN -> "W"
            ERROR -> "E"
        }
}

data class DiagEntry(
    /**
     * Monotonic id, unique for the process lifetime. Used as the `LazyColumn`
     * key: timestamp + text is **not** unique -- e.g. all four engines log the
     * same probe result inside the same millisecond, which collides and throws
     * ("Key ... was already used").
     */
    val seq: Long,
    val timestampMs: Long,
    val level: DiagLevel,
    val tag: String,
    val message: String,
)

/**
 * In-app diagnostic log buffer.
 *
 * Why this exists: engines run fine on the emulator but misbehave on real
 * hardware (see `docs/mnn.md`), and `adb logcat` is not always available at the
 * moment the bug reproduces. This keeps the last N entries in memory **and**
 * mirrors them to a rolling file so the user can hit "share" and hand the file
 * over without a PC.
 *
 * Design constraints:
 * - **Never throws.** Logging must not be able to break inference. Every public
 *   entry point swallows its own failures.
 * - **Bounded memory.** Ring buffer capped at [MAX_ENTRIES]; oldest entries drop.
 * - **Cheap by default.** Entries are appended under one lock; nothing is
 *   rendered or written to disk on the hot path.
 * - **Toggleable.** [setEnabled] gates everything.
 */
object DiagLogger {

    private const val MAX_ENTRIES = 2000

    private const val LOG_FILE_NAME = "droidllm-log.txt"

    /** Mirror of the same buffer to logcat so adb-based capture still works. */
    private const val LOGCAT_TAG = "DroidLLM"

    private val lock = Any()
    private val buffer = ArrayDeque<DiagEntry>(MAX_ENTRIES)
    private val enabled = AtomicBoolean(true)
    private val sequence = AtomicLong(0)
    private val entriesFlow = MutableStateFlow<List<DiagEntry>>(emptyList())

    /** Observer-facing view of the buffer, newest entry last. */
    val entries: StateFlow<List<DiagEntry>> = entriesFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Directory for the rolling `.txt`, set once from `Application.onCreate`.
     *
     * Writing happens only in [flushToFile], never inside [log]: inference is
     * sensitive to file IO on the hot path (AGENTS.md "性能热路径禁止做文件 IO").
     */
    @Volatile
    private var logDir: File? = null

    fun attachStorage(context: Context) {
        logDir = runCatching { File(context.filesDir, "logs").apply { mkdirs() } }.getOrNull()
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun d(tag: String, message: String) = log(DiagLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = log(DiagLevel.INFO, tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) =
        log(DiagLevel.WARN, tag, withCause(message, error))

    fun e(tag: String, message: String, error: Throwable? = null) =
        log(DiagLevel.ERROR, tag, withCause(message, error))

    fun log(level: DiagLevel, tag: String, message: String) {
        if (!enabled.get()) return
        // Always mirror to logcat first: this works even if the in-app buffer is
        // later cleared, and costs nothing when nobody reads it.
        mirrorToLogcat(level, tag, message)
        val entry = DiagEntry(
            seq = sequence.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            entriesFlow.value = buffer.toList()
        }
    }

    /** Snapshot of the buffer as plain lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) {
        buffer.map { render(it) }
    }

    fun snapshotText(): String = snapshot().joinToString("\n")

    /**
     * Current buffer content as a shareable report, with a small device header.
     *
     * [engineLog] is an optional detached snapshot (see [EngineLogcatCapture])
     * appended after the buffer. It is deliberately **not** merged into the ring
     * buffer: our own lines are mirrored to logcat, so a capture fed back here
     * would be re-captured on the next dump.
     */
    fun reportText(header: List<String> = emptyList(), engineLog: String? = null): String = buildString {
        appendLine("DroidLLM diagnostic log")
        appendLine("generated: ${stamp()}")
        header.forEach { appendLine(it) }
        appendLine("entries: ${synchronized(lock) { buffer.size }}")
        appendLine("-".repeat(48))
        append(snapshotText())
        if (!engineLog.isNullOrBlank()) {
            appendLine()
            appendLine()
            appendLine("=".repeat(48))
            appendLine(EngineLogcatCapture.sectionTitle())
            appendLine("=".repeat(48))
            append(engineLog)
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            entriesFlow.value = emptyList()
        }
    }

    /** Absolute path of the rolling log file, or null when storage is unattached. */
    fun logFilePath(): String? = logDir?.let { File(it, LOG_FILE_NAME).absolutePath }

    /**
     * Writes the current buffer to `filesDir/logs/<name>` and returns the file.
     * Call this off the main thread; it is called from the share action and on
     * screen exit, not per log line.
     */
    fun flushToFile(header: List<String> = emptyList(), engineLog: String? = null): File? {
        val dir = logDir ?: return null
        return runCatching {
            val target = File(dir, LOG_FILE_NAME)
            target.writeText(reportText(header, engineLog))
            target
        }.getOrNull()
    }

    // SimpleDateFormat is not thread-safe; share() runs on IO while
    // copyToClipboard() formats on the main thread.
    private fun time(ms: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ms)) }

    private fun stamp(): String = synchronized(stampFormat) { stampFormat.format(Date()) }

    private fun render(entry: DiagEntry): String =
        "${time(entry.timestampMs)} ${entry.level.tag}/${entry.tag}: ${entry.message}"

    private fun withCause(message: String, error: Throwable?): String {
        if (error == null) return message
        return "$message | ${error.javaClass.simpleName}: ${error.message}"
    }

    private fun mirrorToLogcat(level: DiagLevel, tag: String, message: String) {
        try {
            when (level) {
                DiagLevel.DEBUG -> Log.d(LOGCAT_TAG, "[$tag] $message")
                DiagLevel.INFO -> Log.i(LOGCAT_TAG, "[$tag] $message")
                DiagLevel.WARN -> Log.w(LOGCAT_TAG, "[$tag] $message")
                DiagLevel.ERROR -> Log.e(LOGCAT_TAG, "[$tag] $message")
            }
        } catch (_: Throwable) {
            // Logging must never be able to break the caller.
        }
    }
}

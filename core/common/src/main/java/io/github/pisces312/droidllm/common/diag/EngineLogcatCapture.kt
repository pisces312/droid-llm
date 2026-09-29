package io.github.pisces312.droidllm.common.diag

import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * Dumps this process's own logcat buffers. This is the only path that surfaces
 * native engine logs inside the app.
 *
 * Why logcat and not a callback (see `docs/ENGINE_INTEGRATION.md` §诊断):
 * - LiteRT-LM ships as a closed AAR and offers no log hook at all; MNN's
 *   `MNN_PRINT` / `MNN_ERROR` are compile-time macros hard-wired to
 *   `__android_log_print` (there is no `SetLogHandler`), and Genie's wrapper
 *   only calls `__android_log_print`. llama.cpp is the sole engine with a real
 *   `llama_log_set` callback. Reading logcat is therefore the one approach that
 *   covers all four engines without touching their native sources.
 * - Since Android 4.1 an app may read its **own** log lines without the
 *   `READ_LOGS` permission. logd filters a non-privileged reader down to its own
 *   uid, so nothing from other apps is exposed. Filtering on the host with
 *   `grep` is the anti-pattern -- it moves every line across then discards 99%.
 * - `-d` dumps and exits rather than streaming, which matches the
 *   "reproduce, then collect" workflow. `-t` bounds each dump so a noisy
 *   session cannot exhaust memory.
 *
 * **Two dumps, and the crash one deliberately has no `--pid`.** A native crash
 * leaves its stack in the `crash` buffer, written by `crash_dump` -- a separate
 * process. Measured on API 34: reading `-b crash` with `--pid=<app>` returns
 * **0 lines**, without it **73** (pids of the killed app and of `crash_dump`).
 * Narrowing by pid therefore silently deletes exactly the evidence this class
 * exists to collect, which is also why [capture] runs `main,system` and `crash`
 * separately instead of one `-b main,system,crash` call.
 *
 * The crash section is emitted **last** on purpose: [keepTail] drops the head
 * when the dump is oversized, so writing it last is what keeps it alive.
 *
 * The captured text deliberately is **not** written back into [DiagLogger]:
 * our own entries are mirrored to logcat under the `DroidLLM` tag, so feeding a
 * capture into the buffer would re-capture it on the next dump and grow without
 * bound. Keep captures as detached snapshots.
 *
 * Best-effort by contract: returns null on any failure and never throws.
 */
object EngineLogcatCapture {

    /** Tail kept when a session produced more lines than this. */
    private const val MAX_LINES = 4000

    /** Hard cap on returned text; the tail is kept, the head is dropped. */
    private const val MAX_CHARS = 512 * 1024

    private const val TIMEOUT_SECONDS = 5L

    /** Buffers written by the running process; safe to narrow by pid. */
    private const val PROCESS_BUFFERS = "main,system"

    /** Buffers written by `crash_dump` on behalf of the process; must not be narrowed. */
    private const val CRASH_BUFFER = "crash"

    /**
     * Blocking. Call from a background dispatcher.
     *
     * @return the combined `logcat -v time` dump, or null when neither buffer
     *   could be read (no output, exec refused, or timeout).
     */
    fun capture(): String? = runCatching {
        val pid = Process.myPid()
        val sections = buildList {
            dump(buffers = PROCESS_BUFFERS, pid = pid)?.let {
                add("----- $PROCESS_BUFFERS (--pid=$pid) -----\n$it")
            }
            dump(buffers = CRASH_BUFFER, pid = null)?.let {
                add("----- $CRASH_BUFFER (崩溃/tombstone，不按 pid 过滤) -----\n$it")
            }
        }
        sections.joinToString("\n\n").ifBlank { null }
    }.getOrNull()?.let(::keepTail)

    /** Header placed above the dump when it is attached to a report. */
    fun sectionTitle(): String = "引擎内部日志（logcat -d，main/system + crash 缓冲，含 native）"

    /**
     * One `logcat -d` run. `pid = null` lets the crash buffer through: its lines
     * carry `crash_dump`'s pid, so `--pid` would drop every one of them.
     */
    private fun dump(buffers: String, pid: Int?): String? = runCatching {
        val args = mutableListOf("logcat", "-d", "-b", buffers, "-v", "time")
        if (pid != null) args += "--pid=$pid"
        args += listOf("-t", MAX_LINES.toString())

        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        // Drain before waiting: if the child filled the pipe buffer it would
        // block forever on write while we block on waitFor.
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
        }
        text.trim().ifBlank { null }
    }.getOrNull()

    /** Keeps the newest lines when the dump exceeds [MAX_CHARS]. */
    private fun keepTail(text: String): String =
        if (text.length <= MAX_CHARS) {
            text
        } else {
            "(前 ${text.length - MAX_CHARS} 字符已截断，仅保留最新部分)\n" +
                text.substring(text.length - MAX_CHARS)
        }
}

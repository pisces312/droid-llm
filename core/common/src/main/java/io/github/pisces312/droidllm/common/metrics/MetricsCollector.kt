package io.github.pisces312.droidllm.common.metrics

import io.github.pisces312.droidllm.engineapi.EngineMetrics
import io.github.pisces312.droidllm.engineapi.InferenceConfig

/**
 * Engine-reported stage timings, in microseconds.
 *
 * These come from the engine's own instrumentation (e.g. MNN's
 * `LlmContext::prefill_us` / `decode_us`) and measure pure compute, without the
 * per-token JNI callback / UI dispatch overhead that a wall clock would include.
 * When present they take precedence over the wall-clock fallback so numbers stay
 * comparable with the engine's native demos.
 *
 * @param prefillUs duration of the prefill forward pass — effectively TTFT.
 * @param decodeUs duration of the decode loop that produced the tokens.
 * @param ttfaUs time to first emitted piece, when the engine reports it
 *   (0 / absent on backends that do not instrument it).
 */
data class NativeTiming(
    val prefillUs: Long,
    val decodeUs: Long,
    val ttfaUs: Long = 0L,
)

/**
 * Canonical TTFT / prefill / decode timing.
 *
 * Contract (DESIGN.md §2 + IMPLEMENTATION.md §9.4):
 * - TTFT starts at generate() request emission and ends at the first token.
 * - Model load and chat-template formatting are NOT part of TTFT.
 * - prefill_tps = promptTokens / prefillSeconds
 * - decode_tps = (generatedTokens - 1) / decodeSeconds
 *
 * The durations come from [NativeTiming] when the engine reports them, and from
 * the wall clock otherwise. The definitions are documented for users under
 * 设置 → 指标说明.
 */
class MetricsCollector {

    private var startNs: Long = 0L
    private var firstTokenNs: Long = 0L
    private var lastTokenNs: Long = 0L
    private val tokenIntervalsMs = mutableListOf<Double>()
    private var promptTokens: Int = 0

    fun onGenerateStart(promptTokens: Int) {
        startNs = System.nanoTime()
        firstTokenNs = 0L
        lastTokenNs = 0L
        tokenIntervalsMs.clear()
        this.promptTokens = promptTokens.coerceAtLeast(0)
    }

    fun onToken() {
        val now = System.nanoTime()
        if (firstTokenNs == 0L) {
            firstTokenNs = now
            lastTokenNs = now
            return
        }
        tokenIntervalsMs += (now - lastTokenNs) / 1_000_000.0
        lastTokenNs = now
    }

    /** Call after tokenize/prefill so prefill_tps can use the real count. */
    fun onPromptTokenCount(promptTokens: Int) {
        this.promptTokens = promptTokens.coerceAtLeast(0)
    }

    fun build(
        generatedTokens: Int,
        loadMs: Long? = null,
        rssMbLoad: Long? = null,
        rssMbPeak: Long? = null,
        effectiveConfig: InferenceConfig? = null,
        warnings: List<String> = emptyList(),
        native: NativeTiming? = null,
    ): EngineMetrics {
        val wallTtftMs = if (firstTokenNs != 0L) (firstTokenNs - startNs) / 1_000_000 else null
        val wallTotalMs = if (lastTokenNs != 0L) (lastTokenNs - startNs) / 1_000_000 else null

        // Engine-reported stage timings win over the wall clock: they exclude the
        // per-token callback / UI dispatch cost, which is exactly what makes the
        // wall-clock rate look slower than the engine's own benchmark output.
        val nativePrefillMs = native?.prefillUs?.takeIf { it > 0 }?.let { it / 1000.0 }
        val nativeDecodeMs = native?.decodeUs?.takeIf { it > 0 }?.let { it / 1000.0 }
        val nativeTtfaMs = native?.ttfaUs?.takeIf { it > 0 }?.let { it / 1000.0 }

        val ttftMs = (
            nativeTtfaMs?.let { it.toLong() }
                ?: nativePrefillMs?.let { it.toLong() }
                ?: wallTtftMs
            )
        val prefillMs = nativePrefillMs ?: wallTtftMs?.toDouble()
        val decodeMs = nativeDecodeMs ?: wallTotalMs?.let { total ->
            wallTtftMs?.let { (total - it).toDouble() }
        }

        val prefillTps = if (prefillMs != null && prefillMs > 0 && promptTokens > 0) {
            promptTokens / (prefillMs / 1000.0)
        } else {
            null
        }
        val decodeTps = if (generatedTokens > 1 && decodeMs != null && decodeMs > 0) {
            (generatedTokens - 1) / (decodeMs / 1000.0)
        } else {
            null
        }
        val perTokenP50 = tokenIntervalsMs.sorted().let { list ->
            if (list.isEmpty()) null else list[list.size / 2]
        }
        return EngineMetrics(
            loadMs = loadMs,
            ttftMs = ttftMs,
            prefillTps = prefillTps,
            decodeTps = decodeTps,
            perTokenMsP50 = perTokenP50,
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            rssMbLoad = rssMbLoad,
            rssMbPeak = rssMbPeak,
            effectiveConfig = effectiveConfig,
            warnings = warnings,
        )
    }
}

/** Process-level RSS in MB via /proc/self/status. */
object RssReader {
    fun rssMb(): Long? {
        return runCatching {
            val status = java.io.File("/proc/self/status").readText()
            val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
            val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return null
            kb / 1024
        }.getOrNull()
    }
}

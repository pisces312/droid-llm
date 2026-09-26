package io.github.pisces312.droidllm.common.metrics

import io.github.pisces312.droidllm.engineapi.EngineMetrics
import io.github.pisces312.droidllm.engineapi.InferenceConfig

/**
 * Canonical TTFT / prefill / decode timing.
 *
 * Contract (DESIGN.md §2 + IMPLEMENTATION.md §9.4):
 * - TTFT starts at generate() request emission and ends at the first token.
 * - Model load and chat-template formatting are NOT part of TTFT.
 * - prefill_tps = promptTokens / (ttftMs / 1000)
 * - decode_tps = (generatedTokens - 1) / ((totalMs - ttftMs) / 1000)
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
    ): EngineMetrics {
        val ttftMs = if (firstTokenNs != 0L) (firstTokenNs - startNs) / 1_000_000 else null
        val totalMs = if (lastTokenNs != 0L) (lastTokenNs - startNs) / 1_000_000 else null
        val prefillTps = if (ttftMs != null && ttftMs > 0 && promptTokens > 0) {
            promptTokens / (ttftMs / 1000.0)
        } else {
            null
        }
        val decodeTps = if (generatedTokens > 1 && ttftMs != null && totalMs != null && totalMs > ttftMs) {
            (generatedTokens - 1) / ((totalMs - ttftMs) / 1000.0)
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

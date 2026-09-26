package io.github.pisces312.droidllm.benchmark

import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel

/** Built-in benchmark case ids (DESIGN.md §2.2). */
enum class BenchCaseId(val label: String) {
    LOAD("L 加载"),
    PREFILL("P Prefill"),
    DECODE("D Decode"),
    SUSTAIN("T 持续TPS"),
}

data class BenchPrompt(
    val id: String,
    val label: String,
    val text: String,
)

data class BenchTarget(
    val engineId: EngineId,
    val model: LocalModel,
)

data class BenchmarkSpec(
    val targets: List<BenchTarget>,
    val cases: Set<BenchCaseId> = setOf(BenchCaseId.LOAD, BenchCaseId.PREFILL, BenchCaseId.DECODE),
    val prompt: BenchPrompt = BenchmarkPrompts.default,
    val warmup: Int = 1,
    val runs: Int = 3,
    /** Tokens to generate for P/D; T uses this per round. */
    val maxNewTokens: Int = 128,
    /** Short prompt for D/T (prefill uses the long [prompt]). */
    val decodePromptText: String = "用一句话介绍你自己。",
    val sustainRounds: Int = 3,
) {
    init {
        require(targets.isNotEmpty()) { "targets must not be empty" }
        require(warmup >= 0) { "warmup must be >= 0" }
        require(runs >= 1) { "runs must be >= 1" }
        require(maxNewTokens >= 1) { "maxNewTokens must be >= 1" }
    }
}

/** Single timed sample (one run). */
data class CaseSample(
    val loadMs: Long? = null,
    val ttftMs: Long? = null,
    val prefillTps: Double? = null,
    val decodeTps: Double? = null,
    val perTokenMsP50: Double? = null,
    val generatedTokens: Int = 0,
    val promptTokens: Int = 0,
    val textPreview: String = "",
    val error: String? = null,
)

/** Aggregated result for one (engine, model, case). */
data class CaseResult(
    val caseId: BenchCaseId,
    val samples: List<CaseSample>,
    val loadMs: Long? = null,
    val ttftMs: Long? = null,
    val prefillTps: Double? = null,
    val decodeTps: Double? = null,
    val perTokenMsP50: Double? = null,
)

data class TargetResult(
    val engineId: EngineId,
    val engineDisplayName: String,
    val model: LocalModel,
    val cases: Map<BenchCaseId, CaseResult>,
    val rssMbBaseline: Long?,
    val rssMbLoad: Long?,
    val rssMbPeak: Long?,
    val tempCStart: Double?,
    val tempCEnd: Double?,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

data class BenchmarkReport(
    val runId: String,
    val timestampMs: Long,
    val specSummary: String,
    val targets: List<TargetResult>,
    val soc: String?,
    val sdkInt: Int,
)

sealed class BenchProgress {
    data class Starting(val message: String) : BenchProgress()
    data class TargetStarted(val engineId: EngineId, val modelName: String, val index: Int, val total: Int) : BenchProgress()
    data class CaseRunning(
        val engineId: EngineId,
        val caseId: BenchCaseId,
        val sampleIndex: Int,
        val sampleTotal: Int,
        val tpsHint: Double?,
    ) : BenchProgress()
    data class TargetFinished(val engineId: EngineId, val error: String? = null) : BenchProgress()
    data class Finished(val report: BenchmarkReport) : BenchProgress()
    data class Failed(val message: String) : BenchProgress()
}

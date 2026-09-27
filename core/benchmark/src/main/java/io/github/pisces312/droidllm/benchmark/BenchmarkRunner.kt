package io.github.pisces312.droidllm.benchmark

import io.github.pisces312.droidllm.common.bench.BenchmarkDao
import io.github.pisces312.droidllm.common.bench.BenchmarkRunEntity
import io.github.pisces312.droidllm.common.bench.SessionRegistry
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.common.metrics.RssReader
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.GenerateResult
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.labelledName
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.SessionHandle
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates L/P/D/(T) benchmark runs one engine at a time.
 *
 * Contract (DESIGN.md §2):
 * - Force-unload every resident session before RSS baseline and before each target.
 * - warmup + timed runs, report medians.
 * - Never swallow engine errors into fake numbers; failed samples are recorded.
 */
@Singleton
class BenchmarkRunner @Inject constructor(
    private val engines: Set<@JvmSuppressWildcards LlmEngine>,
    private val deviceProbe: DeviceProbe,
    private val sessionRegistry: SessionRegistry,
    private val dao: BenchmarkDao,
) {

    suspend fun run(
        spec: BenchmarkSpec,
        onProgress: (BenchProgress) -> Unit = {},
        awaitIfPaused: suspend () -> Unit = {},
    ): BenchmarkReport {
        val probe = deviceProbe.probe()
        val timestampMs = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        onProgress(BenchProgress.Starting("准备中：卸载已有模型…"))

        awaitIfPaused()
        sessionRegistry.unloadAll()
        val rssBaseline = RssReader.rssMb()
        val tempStart = deviceProbe.batteryTempC()?.toDouble()
        val targetResults = mutableListOf<TargetResult>()

        spec.targets.forEachIndexed { index, target ->
            awaitIfPaused()
            onProgress(
                BenchProgress.TargetStarted(
                    engineId = target.engineId,
                    modelName = target.model.displayName,
                    index = index + 1,
                    total = spec.targets.size,
                ),
            )
            val result = runOneTarget(spec, target, rssBaseline, onProgress, awaitIfPaused)
            targetResults += result
            onProgress(BenchProgress.TargetFinished(result.engineId, result.error))
        }

        val report = BenchmarkReport(
            runId = runId,
            timestampMs = timestampMs,
            specSummary = summarizeSpec(spec),
            targets = targetResults,
            soc = probe.socModel,
            sdkInt = probe.sdkInt,
        )
        persist(report, spec, tempStart)
        onProgress(BenchProgress.Finished(report))
        return report
    }

    private suspend fun runOneTarget(
        spec: BenchmarkSpec,
        target: BenchTarget,
        rssBaseline: Long?,
        onProgress: (BenchProgress) -> Unit,
        awaitIfPaused: suspend () -> Unit,
    ): TargetResult {
        // Exclusive residency: unload everyone else first.
        sessionRegistry.unloadAll()
        val engine = engines.firstOrNull { it.id == target.engineId }
            ?: return TargetResult(
                engineId = target.engineId,
                engineDisplayName = target.engineId.name,
                model = target.model,
                cases = emptyMap(),
                rssMbBaseline = rssBaseline,
                rssMbLoad = null,
                rssMbPeak = null,
                tempCStart = deviceProbe.batteryTempC()?.toDouble(),
                tempCEnd = null,
                error = "engine not registered: ${target.engineId}",
            )

        val tempStart = deviceProbe.batteryTempC()?.toDouble()
        val warnings = mutableListOf<String>()
        val config = InferenceConfig(maxNewTokens = spec.maxNewTokens)
        val cases = linkedMapOf<BenchCaseId, CaseResult>()
        var rssLoad: Long? = null
        var rssPeak = RssReader.rssMb()
        var session: SessionHandle? = null
        var loadMs: Long? = null

        try {
            awaitIfPaused()
            val loadResult = measureLoad(engine, target.model, config)
            loadMs = loadResult.first
            session = loadResult.second
            if (session == null) {
                return TargetResult(
                    engineId = target.engineId,
                    engineDisplayName = engine.labelledName,
                    model = target.model,
                    cases = emptyMap(),
                    rssMbBaseline = rssBaseline,
                    rssMbLoad = null,
                    rssMbPeak = rssPeak,
                    tempCStart = tempStart,
                    tempCEnd = deviceProbe.batteryTempC()?.toDouble(),
                    warnings = warnings,
                    error = loadResult.third ?: "load failed",
                )
            }
            sessionRegistry.register(engine, session)
            rssLoad = RssReader.rssMb()
            rssPeak = maxOf(rssPeak ?: 0L, rssLoad ?: 0L)

            if (spec.cases.contains(BenchCaseId.LOAD)) {
                cases[BenchCaseId.LOAD] = CaseResult(
                    caseId = BenchCaseId.LOAD,
                    samples = listOf(
                        CaseSample(loadMs = loadMs, textPreview = "load only"),
                    ),
                    loadMs = loadMs,
                )
            }

            val runCases = spec.cases.filter { it != BenchCaseId.LOAD }
            var lastDecodeTps: Double? = null
            for (caseId in runCases) {
                val totalSamples = spec.warmup + spec.runs +
                    if (caseId == BenchCaseId.SUSTAIN) spec.sustainRounds - 1 else 0
                val samples = mutableListOf<CaseSample>()
                var sampleIndex = 0

                // warmup
                repeat(spec.warmup) {
                    sampleIndex++
                    awaitIfPaused()
                    onProgress(
                        BenchProgress.CaseRunning(
                            target.engineId, caseId, sampleIndex, totalSamples, lastDecodeTps,
                        ),
                    )
                    val s = measureCase(caseId, engine, session, spec)
                    if (s.error != null) warnings += "warmup ${caseId.name}: ${s.error}"
                    s.decodeTps?.let { lastDecodeTps = it }
                    rssPeak = maxOf(rssPeak ?: 0L, RssReader.rssMb() ?: 0L)
                }

                // timed runs
                repeat(spec.runs) {
                    sampleIndex++
                    awaitIfPaused()
                    onProgress(
                        BenchProgress.CaseRunning(
                            target.engineId, caseId, sampleIndex, totalSamples, lastDecodeTps,
                        ),
                    )
                    val s = measureCase(caseId, engine, session, spec)
                    samples += s
                    s.decodeTps?.let { lastDecodeTps = it }
                    rssPeak = maxOf(rssPeak ?: 0L, RssReader.rssMb() ?: 0L)
                }

                // T: extra rounds beyond the first (already in runs if runs>=1)
                if (caseId == BenchCaseId.SUSTAIN) {
                    val extra = (spec.sustainRounds - spec.runs).coerceAtLeast(0)
                    repeat(extra) {
                        sampleIndex++
                        awaitIfPaused()
                        onProgress(
                            BenchProgress.CaseRunning(
                                target.engineId, caseId, sampleIndex, totalSamples, lastDecodeTps,
                            ),
                        )
                        val s = measureCase(caseId, engine, session, spec)
                        samples += s
                        s.decodeTps?.let { lastDecodeTps = it }
                        rssPeak = maxOf(rssPeak ?: 0L, RssReader.rssMb() ?: 0L)
                    }
                }

                cases[caseId] = aggregate(caseId, samples, loadMs)
            }
        } catch (t: Throwable) {
            return TargetResult(
                engineId = target.engineId,
                engineDisplayName = engine.labelledName,
                model = target.model,
                cases = cases,
                rssMbBaseline = rssBaseline,
                rssMbLoad = rssLoad,
                rssMbPeak = rssPeak,
                tempCStart = tempStart,
                tempCEnd = deviceProbe.batteryTempC()?.toDouble(),
                warnings = warnings,
                error = t.message ?: t.toString(),
            )
        } finally {
            session?.let {
                sessionRegistry.unregister(it)
                runCatching { engine.unload(it) }
            }
        }

        return TargetResult(
            engineId = target.engineId,
            engineDisplayName = engine.labelledName,
            model = target.model,
            cases = cases,
            rssMbBaseline = rssBaseline,
            rssMbLoad = rssLoad,
            rssMbPeak = rssPeak,
            tempCStart = tempStart,
            tempCEnd = deviceProbe.batteryTempC()?.toDouble(),
            warnings = warnings,
        )
    }

    private suspend fun measureLoad(
        engine: LlmEngine,
        model: LocalModel,
        config: InferenceConfig,
    ): Triple<Long?, SessionHandle?, String?> {
        val start = System.nanoTime()
        return try {
            val handle = engine.load(model, config)
            val ms = (System.nanoTime() - start) / 1_000_000
            Triple(ms, handle, null)
        } catch (e: EngineException) {
            Triple(null, null, e.message ?: e.toString())
        } catch (t: Throwable) {
            Triple(null, null, t.message ?: t.toString())
        }
    }

    private suspend fun measureCase(
        caseId: BenchCaseId,
        engine: LlmEngine,
        session: SessionHandle,
        spec: BenchmarkSpec,
    ): CaseSample {
        val promptText = when (caseId) {
            BenchCaseId.PREFILL -> spec.prompt.text
            BenchCaseId.DECODE, BenchCaseId.SUSTAIN -> spec.decodePromptText
            BenchCaseId.LOAD -> return CaseSample(textPreview = "n/a")
        }
        val maxNew = when (caseId) {
            BenchCaseId.PREFILL -> 16.coerceAtMost(spec.maxNewTokens)
            else -> spec.maxNewTokens
        }
        val request = GenerateRequest(
            messages = listOf(ChatMessage(ChatRole.USER, promptText)),
            config = InferenceConfig(maxNewTokens = maxNew),
        )

        val resultRef = AtomicReference<GenerateResult?>(null)
        val errorRef = AtomicReference<EngineException?>(null)
        val done = CompletableDeferred<Unit>()

        val job = try {
            engine.generate(session, request) { event ->
                when (event) {
                    is EngineEvent.Done -> {
                        resultRef.set(event.result)
                        done.complete(Unit)
                    }
                    is EngineEvent.Error -> {
                        errorRef.set(event.cause)
                        done.complete(Unit)
                    }
                    is EngineEvent.Token -> Unit
                }
            }
        } catch (t: Throwable) {
            return CaseSample(error = t.message ?: t.toString())
        }

        val finished = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
            done.await()
        } != null
        if (!finished) {
            job.cancel()
            return CaseSample(error = "timeout after ${GENERATE_TIMEOUT_MS}ms")
        }

        errorRef.get()?.let {
            return CaseSample(error = it.message ?: it.toString())
        }
        val r = resultRef.get()
            ?: return CaseSample(error = "no result")
        return CaseSample(
            loadMs = r.metrics.loadMs,
            ttftMs = r.metrics.ttftMs,
            prefillTps = r.metrics.prefillTps,
            decodeTps = r.metrics.decodeTps,
            perTokenMsP50 = r.metrics.perTokenMsP50,
            generatedTokens = r.generatedTokens,
            promptTokens = r.promptTokens,
            textPreview = r.text.take(80),
            error = null,
        )
    }

    private fun aggregate(
        caseId: BenchCaseId,
        samples: List<CaseSample>,
        loadMs: Long?,
    ): CaseResult {
        val ok = samples.filter { it.error == null }
        return CaseResult(
            caseId = caseId,
            samples = samples,
            loadMs = loadMs ?: ok.medianOfLong { it.loadMs },
            ttftMs = ok.medianOfLong { it.ttftMs },
            prefillTps = ok.medianOfDouble { it.prefillTps },
            decodeTps = ok.medianOfDouble { it.decodeTps },
            perTokenMsP50 = ok.medianOfDouble { it.perTokenMsP50 },
        )
    }

    private fun List<CaseSample>.medianOfLong(selector: (CaseSample) -> Long?): Long? {
        val v = mapNotNull(selector).sorted()
        return if (v.isEmpty()) null else v[v.size / 2]
    }

    private fun List<CaseSample>.medianOfDouble(selector: (CaseSample) -> Double?): Double? {
        val v = mapNotNull(selector).sorted()
        return if (v.isEmpty()) null else v[v.size / 2]
    }

    private fun summarizeSpec(spec: BenchmarkSpec): String {
        val cases = spec.cases.joinToString("/") { it.name }
        return "cases=$cases warmup=${spec.warmup} runs=${spec.runs} " +
            "maxNewTokens=${spec.maxNewTokens} prompt=${spec.prompt.id} targets=${spec.targets.size}"
    }

    private suspend fun persist(report: BenchmarkReport, spec: BenchmarkSpec, tempStart: Double?) {
        for (t in report.targets) {
            val payload = JSONObject().apply {
                put("runId", report.runId)
                put("spec", report.specSummary)
                put("warnings", JSONArray(t.warnings))
                put(
                    "cases",
                    JSONObject().apply {
                        for ((id, case) in t.cases) {
                            put(
                                id.name,
                                JSONObject().apply {
                                    put("loadMs", case.loadMs ?: JSONObject.NULL)
                                    put("ttftMs", case.ttftMs ?: JSONObject.NULL)
                                    put("prefillTps", case.prefillTps ?: JSONObject.NULL)
                                    put("decodeTps", case.decodeTps ?: JSONObject.NULL)
                                    put("perTokenMsP50", case.perTokenMsP50 ?: JSONObject.NULL)
                                    put(
                                        "samples",
                                        JSONArray().apply {
                                            case.samples.forEach { s ->
                                                put(
                                                    JSONObject().apply {
                                                        put("ttftMs", s.ttftMs ?: JSONObject.NULL)
                                                        put("decodeTps", s.decodeTps ?: JSONObject.NULL)
                                                        put("generatedTokens", s.generatedTokens)
                                                        put("error", s.error ?: JSONObject.NULL)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
            val primary = t.cases.values
            dao.insert(
                BenchmarkRunEntity(
                    runId = "${report.runId}-${t.engineId.name}",
                    timestampMs = report.timestampMs,
                    engineId = t.engineId.name,
                    modelName = t.model.displayName,
                    modelPath = pathOf(t.model),
                    quantHint = t.model.quantHint,
                    caseId = spec.cases.joinToString("+") { it.name },
                    warmup = spec.warmup,
                    runs = spec.runs,
                    loadMs = t.cases[BenchCaseId.LOAD]?.loadMs ?: t.cases.values.firstOrNull()?.loadMs,
                    ttftMs = primary.firstNotNullOfOrNull { it.ttftMs },
                    prefillTps = primary.firstNotNullOfOrNull { it.prefillTps },
                    decodeTps = primary.firstNotNullOfOrNull { it.decodeTps },
                    perTokenMsP50 = primary.firstNotNullOfOrNull { it.perTokenMsP50 },
                    rssMbBaseline = t.rssMbBaseline,
                    rssMbLoad = t.rssMbLoad,
                    rssMbPeak = t.rssMbPeak,
                    tempC = tempStart ?: t.tempCStart,
                    soc = report.soc,
                    sdkInt = report.sdkInt,
                    payloadJson = payload.toString(),
                ),
            )
        }
    }

    private fun pathOf(model: LocalModel): String = when (val loc = model.location) {
        is ModelLocation.FilePath -> loc.path
        is ModelLocation.AppPrivate -> loc.relativePath
        is ModelLocation.SafUri -> loc.uri
    }

    companion object {
        private const val GENERATE_TIMEOUT_MS = 180_000L
    }
}

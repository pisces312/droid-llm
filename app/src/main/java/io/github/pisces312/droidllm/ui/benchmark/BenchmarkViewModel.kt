package io.github.pisces312.droidllm.ui.benchmark

import io.github.pisces312.droidllm.R

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.benchmark.BenchCaseId
import io.github.pisces312.droidllm.benchmark.BenchProgress
import io.github.pisces312.droidllm.benchmark.BenchPrompt
import io.github.pisces312.droidllm.benchmark.BenchTarget
import io.github.pisces312.droidllm.benchmark.BenchmarkPrompts
import io.github.pisces312.droidllm.benchmark.BenchmarkReport
import io.github.pisces312.droidllm.benchmark.BenchmarkRunner
import io.github.pisces312.droidllm.benchmark.BenchmarkSpec
import io.github.pisces312.droidllm.benchmark.JsonExporter
import io.github.pisces312.droidllm.common.bench.BenchmarkDao
import io.github.pisces312.droidllm.common.bench.BenchmarkRunEntity
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.engineapi.labelledName
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BenchEngineRow(
    val engineId: EngineId,
    val engineName: String,
    val available: Boolean,
    val availabilityLabel: String,
    val models: List<LocalModel>,
    val selectedModelId: String?,
    val included: Boolean,
)

data class BenchmarkUiState(
    val engineRows: List<BenchEngineRow> = emptyList(),
    val promptId: String = BenchmarkPrompts.default.id,
    val cases: Set<BenchCaseId> = setOf(BenchCaseId.LOAD, BenchCaseId.PREFILL, BenchCaseId.DECODE),
    val warmup: Int = 1,
    val runs: Int = 3,
    val maxNewTokens: Int = 128,
    val paramsExpanded: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val progressTitle: String = "",
    val progressDetail: String = "",
    val progressFraction: Float? = null,
    val lastDecodeTps: Double? = null,
    val report: BenchmarkReport? = null,
    val history: List<BenchmarkRunEntity> = emptyList(),
    val showCoolingBanner: Boolean = true,
    val snackbar: String? = null,
    val statusMessage: String? = null,
)

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val engines: Set<@JvmSuppressWildcards LlmEngine>,
    private val modelStore: ModelPathStore,
    private val deviceProbe: DeviceProbe,
    private val runner: BenchmarkRunner,
    private val dao: BenchmarkDao,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var targetIndex = 1
    private var targetTotal = 1
    private var currentCaseId: BenchCaseId? = null
    private var casePos = 0

    init {
        viewModelScope.launch {
            val ctx = deviceProbe.probe()
            val rows = engines.map { engine ->
                val av = engine.probe(ctx)
                BenchEngineRow(
                    engineId = engine.id,
                    engineName = engine.labelledName,
                    available = av is Availability.Available,
                    availabilityLabel = availabilityLabel(av),
                    models = emptyList(),
                    selectedModelId = null,
                    included = av is Availability.Available,
                )
            }.sortedBy { it.engineName }
            _state.value = _state.value.copy(engineRows = rows)
            refreshModels()
        }
        viewModelScope.launch {
            dao.observeAll().collect { list ->
                _state.value = _state.value.copy(history = list)
            }
        }
    }

    private suspend fun refreshModels() {
        val all = modelStore.listModels()
        val rows = _state.value.engineRows.map { row ->
            val models = all.filter { it.engineId == row.engineId }
            val selected = when {
                models.any { it.id == row.selectedModelId } -> row.selectedModelId
                else -> models.firstOrNull()?.id
            }
            val included = row.included && models.isNotEmpty()
            row.copy(models = models, selectedModelId = selected, included = included)
        }.ifEmpty {
            // Keep probe rows even before any model exists.
            _state.value.engineRows
        }
        _state.value = _state.value.copy(engineRows = rows)
    }

    fun toggleEngine(engineId: EngineId, included: Boolean) {
        updateRow(engineId) { row ->
            row.copy(included = included && row.models.isNotEmpty())
        }
    }

    fun selectModel(engineId: EngineId, modelId: String) {
        updateRow(engineId) { it.copy(selectedModelId = modelId) }
    }

    fun selectPrompt(id: String) {
        _state.value = _state.value.copy(promptId = id)
    }

    fun toggleCase(caseId: BenchCaseId, checked: Boolean) {
        val next = if (checked) _state.value.cases + caseId else _state.value.cases - caseId
        if (next.isNotEmpty()) {
            _state.value = _state.value.copy(cases = next)
        }
    }

    fun setWarmup(v: Int) {
        _state.value = _state.value.copy(warmup = v.coerceIn(0, 9))
    }

    fun setRuns(v: Int) {
        _state.value = _state.value.copy(runs = v.coerceIn(1, 9))
    }

    fun setMaxNewTokens(v: Int) {
        _state.value = _state.value.copy(maxNewTokens = v.coerceIn(16, 2048))
    }

    fun toggleParamsExpanded() {
        _state.value = _state.value.copy(paramsExpanded = !_state.value.paramsExpanded)
    }

    fun dismissCoolingBanner() {
        _state.value = _state.value.copy(showCoolingBanner = false)
    }

    fun dismissSnackbar() {
        _state.value = _state.value.copy(snackbar = null)
    }

    fun onAppBackground() {
        if (_state.value.running && !_state.value.paused) {
            _state.value = _state.value.copy(paused = true, progressDetail = appContext.getString(R.string.bench_paused_background))
        }
    }

    fun onAppForeground() {
        // Stay paused; user chooses 继续 / 放弃 (UI_DESIGN.md §5.3).
    }

    fun resume() {
        _state.value = _state.value.copy(paused = false, progressDetail = "")
    }

    fun abandon() {
        _state.value = _state.value.copy(paused = false)
        cancel()
    }

    fun cancel() {
        runJob?.cancel()
        runJob = null
        _state.value = _state.value.copy(
            running = false,
            paused = false,
            progressTitle = "",
            progressDetail = "",
            progressFraction = null,
            statusMessage = appContext.getString(R.string.bench_cancelled),
        )
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clear()
            _state.value = _state.value.copy(snackbar = appContext.getString(R.string.bench_history_cleared))
        }
    }

    fun exportLatest() {
        val report = _state.value.report ?: return
        val dir = appContext.getExternalFilesDir("benchmark") ?: appContext.filesDir
        viewModelScope.launch {
            runCatching { JsonExporter.write(report, dir) }
                .onSuccess { file ->
                    _state.value = _state.value.copy(snackbar = appContext.getString(R.string.bench_exported, file.name))
                }
                .onFailure {
                    _state.value = _state.value.copy(snackbar = appContext.getString(R.string.bench_export_failed, it.message))
                }
        }
    }

    fun startRun() {
        if (_state.value.running) return
        val targets = buildTargets()
        if (targets.isEmpty()) {
            _state.value = _state.value.copy(statusMessage = appContext.getString(R.string.bench_error_no_target))
            return
        }
        val prompt: BenchPrompt = BenchmarkPrompts.byId(_state.value.promptId)
        val spec = BenchmarkSpec(
            targets = targets,
            cases = _state.value.cases,
            prompt = prompt,
            warmup = _state.value.warmup,
            runs = _state.value.runs,
            maxNewTokens = _state.value.maxNewTokens,
        )
        targetIndex = 1
        targetTotal = targets.size
        currentCaseId = null
        casePos = 0
        _state.value = _state.value.copy(
            running = true,
            paused = false,
            report = null,
            statusMessage = null,
            progressTitle = appContext.getString(R.string.bench_preparing),
            progressDetail = "",
            progressFraction = 0f,
            lastDecodeTps = null,
        )
        runJob = viewModelScope.launch {
            try {
                val report = runner.run(
                    spec = spec,
                    onProgress = { p -> onProgress(p) },
                    awaitIfPaused = {
                        while (_state.value.paused && isActive) {
                            delay(80)
                        }
                    },
                )
                _state.value = _state.value.copy(
                    running = false,
                    paused = false,
                    report = report,
                    progressTitle = appContext.getString(R.string.bench_finished),
                    progressDetail = "",
                    progressFraction = 1f,
                    statusMessage = appContext.getString(R.string.bench_finished_count, report.targets.size),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancel()/abandon() already updated UI state.
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    paused = false,
                    progressTitle = "",
                    statusMessage = appContext.getString(R.string.bench_failed, t.message ?: t),
                )
            }
        }
    }

    private fun buildTargets(): List<BenchTarget> {
        return _state.value.engineRows.mapNotNull { row ->
            if (!row.included) return@mapNotNull null
            val model = row.models.firstOrNull { it.id == row.selectedModelId }
                ?: return@mapNotNull null
            BenchTarget(row.engineId, model)
        }
    }

    private fun onProgress(p: BenchProgress) {
        when (p) {
            is BenchProgress.Starting -> {
                _state.value = _state.value.copy(progressTitle = p.message, progressDetail = "")
            }
            is BenchProgress.TargetStarted -> {
                targetIndex = p.index
                targetTotal = p.total
                currentCaseId = null
                casePos = 0
                _state.value = _state.value.copy(
                    progressTitle = appContext.getString(R.string.bench_progress_target, p.index, p.total, p.modelName),
                    progressDetail = appContext.getString(R.string.common_loading),
                    progressFraction = (p.index - 1f) / p.total,
                )
            }
            is BenchProgress.CaseRunning -> {
                if (p.caseId != currentCaseId) {
                    currentCaseId = p.caseId
                    casePos++
                }
                val caseFraction = p.sampleIndex.toFloat() / p.sampleTotal.coerceAtLeast(1)
                val casesEstimate = _state.value.cases.size.coerceAtLeast(1)
                val inner = (casePos - 1 + caseFraction) / casesEstimate
                val fraction = ((targetIndex - 1) + inner) / targetTotal.coerceAtLeast(1)
                _state.value = _state.value.copy(
                    progressTitle = appContext.getString(
                        R.string.bench_progress_case,
                        targetIndex,
                        targetTotal,
                        appContext.getString(p.caseId.labelRes),
                    ),
                    progressDetail = appContext.getString(R.string.bench_progress_sample, p.sampleIndex, p.sampleTotal, _state.value.warmup),
                    progressFraction = fraction,
                    lastDecodeTps = p.tpsHint ?: _state.value.lastDecodeTps,
                )
            }
            is BenchProgress.TargetFinished -> {
                val msg = p.error?.let { appContext.getString(R.string.bench_target_failed, p.engineId.displayName, it) }
                _state.value = _state.value.copy(statusMessage = msg ?: _state.value.statusMessage)
            }
            is BenchProgress.Finished -> {
                _state.value = _state.value.copy(progressTitle = appContext.getString(R.string.bench_finished), progressFraction = 1f)
            }
            is BenchProgress.Failed -> {
                _state.value = _state.value.copy(
                    running = false,
                    progressTitle = "",
                    statusMessage = p.message,
                )
            }
        }
    }

    private fun updateRow(engineId: EngineId, transform: (BenchEngineRow) -> BenchEngineRow) {
        _state.value = _state.value.copy(
            engineRows = _state.value.engineRows.map {
                if (it.engineId == engineId) transform(it) else it
            },
        )
    }

    private fun availabilityLabel(av: Availability): String = when (av) {
        is Availability.Available -> appContext.getString(R.string.engine_available)
        is Availability.MissingDependency -> appContext.getString(R.string.engine_unavailable, av.detail)
        is Availability.UnsupportedSoc -> appContext.getString(R.string.engine_unsupported, av.detail)
        is Availability.ModelNotConfigured -> appContext.getString(R.string.engine_no_model)
        is Availability.InvalidModel -> appContext.getString(R.string.engine_invalid_model, av.detail)
    }
}

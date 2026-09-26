package io.github.pisces312.droidllm.ui.benchmark

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
import io.github.pisces312.droidllm.engineapi.ModelLocation
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
                    engineName = engine.displayName,
                    available = av is Availability.Available,
                    availabilityLabel = availabilityLabel(av),
                    models = emptyList(),
                    selectedModelId = null,
                    included = av is Availability.Available && engine.id != EngineId.FAKE,
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
            val included = row.included && (models.isNotEmpty() || row.engineId == EngineId.FAKE)
            row.copy(models = models, selectedModelId = selected, included = included)
        }.ifEmpty {
            // Keep probe rows even before any model exists.
            _state.value.engineRows
        }
        _state.value = _state.value.copy(engineRows = rows)
    }

    fun toggleEngine(engineId: EngineId, included: Boolean) {
        updateRow(engineId) { row ->
            row.copy(included = included && (row.models.isNotEmpty() || row.engineId == EngineId.FAKE))
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
            _state.value = _state.value.copy(paused = true, progressDetail = "已退后台，自动暂停")
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
            statusMessage = "已取消本次评测",
        )
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clear()
            _state.value = _state.value.copy(snackbar = "已清空评测历史")
        }
    }

    fun exportLatest() {
        val report = _state.value.report ?: return
        val dir = appContext.getExternalFilesDir("benchmark") ?: appContext.filesDir
        viewModelScope.launch {
            runCatching { JsonExporter.write(report, dir) }
                .onSuccess { file ->
                    _state.value = _state.value.copy(snackbar = "已导出 ${file.name}")
                }
                .onFailure {
                    _state.value = _state.value.copy(snackbar = "导出失败：${it.message}")
                }
        }
    }

    fun startRun() {
        if (_state.value.running) return
        val targets = buildTargets()
        if (targets.isEmpty()) {
            _state.value = _state.value.copy(statusMessage = "请先勾选引擎并选择模型")
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
            progressTitle = "准备中…",
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
                    progressTitle = "评测完成",
                    progressDetail = "",
                    progressFraction = 1f,
                    statusMessage = "评测完成，共 ${report.targets.size} 个引擎",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancel()/abandon() already updated UI state.
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    paused = false,
                    progressTitle = "",
                    statusMessage = "评测失败：${t.message ?: t}",
                )
            }
        }
    }

    private fun buildTargets(): List<BenchTarget> {
        return _state.value.engineRows.mapNotNull { row ->
            if (!row.included) return@mapNotNull null
            val model = when {
                row.engineId == EngineId.FAKE && row.models.isEmpty() -> LocalModel(
                    id = "fake-default",
                    engineId = EngineId.FAKE,
                    displayName = "Fake lorem model",
                    location = ModelLocation.AppPrivate("fake"),
                )
                else -> row.models.firstOrNull { it.id == row.selectedModelId } ?: return@mapNotNull null
            }
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
                    progressTitle = "引擎 ${p.index}/${p.total} · ${p.modelName}",
                    progressDetail = "加载中…",
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
                    progressTitle = "引擎 $targetIndex/$targetTotal · ${p.caseId.label}",
                    progressDetail = "样本 ${p.sampleIndex}/${p.sampleTotal}（含 warmup=${_state.value.warmup}）",
                    progressFraction = fraction,
                    lastDecodeTps = p.tpsHint ?: _state.value.lastDecodeTps,
                )
            }
            is BenchProgress.TargetFinished -> {
                val msg = p.error?.let { "${p.engineId.name} 失败：$it" }
                _state.value = _state.value.copy(statusMessage = msg ?: _state.value.statusMessage)
            }
            is BenchProgress.Finished -> {
                _state.value = _state.value.copy(progressTitle = "评测完成", progressFraction = 1f)
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
        is Availability.Available -> "可用"
        is Availability.MissingDependency -> "不可用：${av.detail}"
        is Availability.UnsupportedSoc -> "不支持：${av.detail}"
        is Availability.ModelNotConfigured -> "未配置模型"
        is Availability.InvalidModel -> "模型无效：${av.detail}"
    }
}

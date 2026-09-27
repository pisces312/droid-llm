package io.github.pisces312.droidllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.settings.AppSettings
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.GenerateJob
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.SessionHandle
import io.github.pisces312.droidllm.engineapi.labelledName
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EngineChoice(
    val engine: LlmEngine,
    val displayName: String,
    /** [displayName] plus engine version/commit, used wherever the user reads it. */
    val label: String,
    val available: Boolean,
    val availability: Availability,
)

data class ModelChoice(
    val model: LocalModel,
    val displayName: String,
)

/** One chat turn, with optional metrics attached to the assistant bubble. */
data class ChatUiMessage(
    val role: ChatRole,
    val content: String,
    val ttftMs: Long? = null,
    val decodeTps: Double? = null,
    val error: String? = null,
)

data class SamplingUiState(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val threads: Int = 4,
    val maxNewTokens: Int = 128,
    val backend: Backend = Backend.AUTO,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineSet: Set<@JvmSuppressWildcards LlmEngine>,
    private val modelStore: ModelPathStore,
    private val probe: io.github.pisces312.droidllm.common.device.DeviceProbe,
    private val sessionRegistry: io.github.pisces312.droidllm.common.bench.SessionRegistry,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {

    private val _engines = MutableStateFlow<List<EngineChoice>>(emptyList())
    val engines: StateFlow<List<EngineChoice>> = _engines.asStateFlow()

    private val _selectedEngine = MutableStateFlow<EngineChoice?>(null)
    val selectedEngine: StateFlow<EngineChoice?> = _selectedEngine.asStateFlow()

    private val _models = MutableStateFlow<List<ModelChoice>>(emptyList())
    val models: StateFlow<List<ModelChoice>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelChoice?>(null)
    val selectedModel: StateFlow<ModelChoice?> = _selectedModel.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages: StateFlow<List<ChatUiMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("准备就绪")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _availability = MutableStateFlow("")
    val availability: StateFlow<String> = _availability.asStateFlow()

    private val _sampling = MutableStateFlow(SamplingUiState())
    val sampling: StateFlow<SamplingUiState> = _sampling.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private var session: SessionHandle? = null
    private var sessionEngine: LlmEngine? = null
    private var activeJob: GenerateJob? = null
    private var multiResidency = false
    /** Kept sessions when multi-model residency is on (DESIGN §3.3). */
    private val resident = LinkedHashMap<String, Pair<LlmEngine, SessionHandle>>()

    init {
        viewModelScope.launch {
            val settings = settingsStore.current()
            multiResidency = settings.multiModelResidency
            _sampling.value = settings.toSamplingUi()
            settingsStore.observe().collect { s ->
                multiResidency = s.multiModelResidency
                if (!_generating.value) {
                    _sampling.value = s.toSamplingUi()
                }
            }
        }
        viewModelScope.launch {
            val ctx = probe.probe()
            val choices = engineSet.map { engine ->
                val av = engine.probe(ctx)
                EngineChoice(
                    engine = engine,
                    displayName = engine.displayName,
                    label = engine.labelledName,
                    available = av is Availability.Available,
                    availability = av,
                )
            }.sortedBy { it.displayName }
            _engines.value = choices
            val preferred = choices.firstOrNull { it.available }
                ?: choices.firstOrNull()
            preferred?.let { selectEngine(it) }
        }
        // Keep the picker in sync with the model store (e.g. after market download).
        viewModelScope.launch {
            modelStore.observeModels().collect { all ->
                val engine = _selectedEngine.value?.engine ?: return@collect
                reconcileModels(engine, all)
            }
        }
    }

    private fun AppSettings.toSamplingUi() = SamplingUiState(
        temperature = temperature,
        topK = topK,
        topP = topP,
        threads = threads,
        maxNewTokens = maxNewTokens,
        backend = backend,
    )

    fun selectEngine(choice: EngineChoice) {
        viewModelScope.launch {
            stopGenerate()
            if (!multiResidency) unloadSession()
            _selectedEngine.value = choice
            _availability.value = describeAvailability(choice)
            loadModelsFor(choice.engine)
        }
    }

    fun selectModel(choice: ModelChoice) {
        _selectedModel.value = choice
        viewModelScope.launch {
            stopGenerate()
            if (!multiResidency) unloadSession()
            openSession(choice.model)
        }
    }

    fun updateSampling(transform: (SamplingUiState) -> SamplingUiState) {
        _sampling.value = transform(_sampling.value)
    }

    fun newSession() {
        viewModelScope.launch {
            stopGenerate()
            val handle = session
            val engine = sessionEngine
            if (handle != null && engine != null) {
                runCatching { engine.reset(handle) }
                _status.value = "已新建会话（上下文已清空）"
            } else {
                _status.value = "当前无会话"
            }
            _messages.value = emptyList()
        }
    }

    fun stopGenerate() {
        activeJob?.cancel()
        activeJob = null
        _generating.value = false
    }

    private fun describeAvailability(choice: EngineChoice): String = when (val av = choice.availability) {
        is Availability.Available -> "可用"
        is Availability.MissingDependency -> "不可用：${av.detail}；用带该依赖的构建包重装"
        is Availability.UnsupportedSoc -> "不支持的 SoC：${av.detail}；换骁龙 HTP 机型"
        is Availability.ModelNotConfigured -> "未配置模型：${av.detail}；到「模型」页添加"
        is Availability.InvalidModel -> "模型无效：${av.detail}；检查文件是否完整"
    }

    private suspend fun loadModelsFor(engine: LlmEngine) {
        val all = modelStore.observeModels().first()
        reconcileModels(engine, all)
    }

    /**
     * Rebuild the model picker for [engine] from [all] stored models.
     * Keeps the Fake default entry, and auto-selects (and opens) the first
     * model when nothing valid is selected — e.g. right after a download.
     */
    private fun reconcileModels(engine: LlmEngine, all: List<LocalModel>) {
        var list = all.filter { it.engineId == engine.id }
            .map { ModelChoice(it, it.displayName) }
        if (list.isEmpty() && engine.id == io.github.pisces312.droidllm.engineapi.EngineId.FAKE) {
            val fake = LocalModel(
                id = "fake-default",
                engineId = io.github.pisces312.droidllm.engineapi.EngineId.FAKE,
                displayName = "Fake lorem model",
                location = ModelLocation.AppPrivate("fake"),
            )
            list = listOf(ModelChoice(fake, fake.displayName))
        }
        _models.value = list
        val current = _selectedModel.value
        if (current == null || list.none { it.model.id == current.model.id }) {
            val first = list.firstOrNull()
            _selectedModel.value = first
            if (first != null) {
                viewModelScope.launch { openSession(first.model) }
            }
        }
    }

    private suspend fun openSession(model: LocalModel) {
        val engine = _selectedEngine.value?.engine ?: return
        if (_selectedEngine.value?.available != true) {
            _status.value = "引擎不可用，无法加载模型"
            return
        }
        val key = "${engine.id}:${model.id}"
        if (multiResidency) {
            resident[key]?.let { (eng, handle) ->
                session = handle
                sessionEngine = eng
                _status.value = "已切换到驻留模型 ${model.displayName}"
                return
            }
        }
        _status.value = "加载中…"
        runCatching {
            val handle = engine.load(model, _sampling.value.toConfig())
            session = handle
            sessionEngine = engine
            sessionRegistry.register(engine, handle)
            if (multiResidency) resident[key] = engine to handle
            _status.value = "已加载 ${model.displayName}"
        }.onFailure {
            val err = it.message ?: it.javaClass.simpleName
            _status.value = "加载失败：$err；检查路径与文件完整性"
        }
    }

    private suspend fun unloadSession() {
        if (multiResidency) return
        val handle = session ?: return
        val engine = sessionEngine ?: _selectedEngine.value?.engine
        runCatching { engine?.unload(handle) }
        sessionRegistry.unregister(handle)
        session = null
        sessionEngine = null
    }

    fun send(text: String) {
        val engine = _selectedEngine.value?.engine ?: return
        val handle = session ?: run {
            _status.value = "请先选择可用引擎和模型"
            return
        }
        if (_generating.value) return
        val config = _sampling.value.toConfig()
        val history = _messages.value.map { ChatMessage(it.role, it.content) } +
            ChatMessage(ChatRole.USER, text)
        _messages.value = _messages.value + ChatUiMessage(ChatRole.USER, text)
        _status.value = "生成中…"
        _generating.value = true

        val sb = StringBuilder()
        val startedAt = System.nanoTime()
        var firstTokenNs = 0L
        var tokenCount = 0

        val job = engine.generate(handle, GenerateRequest(history, config)) { event ->
            when (event) {
                is EngineEvent.Token -> {
                    if (tokenCount == 0) firstTokenNs = System.nanoTime()
                    tokenCount++
                    sb.append(event.text)
                    val last = _messages.value.lastOrNull()
                    if (last?.role == ChatRole.ASSISTANT && last.error == null) {
                        _messages.value = _messages.value.dropLast(1) +
                            ChatUiMessage(ChatRole.ASSISTANT, sb.toString())
                    } else {
                        _messages.value = _messages.value +
                            ChatUiMessage(ChatRole.ASSISTANT, sb.toString())
                    }
                }
                is EngineEvent.Done -> {
                    val m = event.result.metrics
                    val ttft = m.ttftMs ?: if (firstTokenNs > 0) {
                        (firstTokenNs - startedAt) / 1_000_000
                    } else null
                    val tps = m.decodeTps
                    val content = sb.toString().ifEmpty { "（空回复，可重试）" }
                    val last = _messages.value.lastOrNull()
                    if (last?.role == ChatRole.ASSISTANT) {
                        _messages.value = _messages.value.dropLast(1) +
                            ChatUiMessage(
                                role = ChatRole.ASSISTANT,
                                content = content,
                                ttftMs = ttft,
                                decodeTps = tps,
                            )
                    }
                    _status.value = buildString {
                        append("完成")
                        ttft?.let { append(" · 首 token 延迟（TTFT）${it}ms") }
                        tps?.let { append(" · %.1f tok/s".format(it)) }
                        if (m.warnings.isNotEmpty()) append(" · ").append(m.warnings.joinToString("；"))
                    }
                    _generating.value = false
                    activeJob = null
                }
                is EngineEvent.Error -> {
                    val cause = event.cause
                    val msg = when (cause) {
                        is EngineException.Cancelled -> "已停止"
                        is EngineException.GenerateFailed ->
                            "生成失败：${cause.message ?: "未知"}；可重试或减小 maxNewTokens"
                        else -> "错误：${cause.message ?: cause.javaClass.simpleName}"
                    }
                    val content = sb.toString()
                    val last = _messages.value.lastOrNull()
                    if (last?.role == ChatRole.ASSISTANT) {
                        _messages.value = _messages.value.dropLast(1) +
                            ChatUiMessage(ChatRole.ASSISTANT, content, error = msg)
                    } else {
                        _messages.value = _messages.value +
                            ChatUiMessage(ChatRole.ASSISTANT, content, error = msg)
                    }
                    _status.value = msg
                    _generating.value = false
                    activeJob = null
                }
            }
        }
        activeJob = job
    }

    private fun SamplingUiState.toConfig() = InferenceConfig(
        maxNewTokens = maxNewTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        threads = threads,
        backend = backend,
    )
}

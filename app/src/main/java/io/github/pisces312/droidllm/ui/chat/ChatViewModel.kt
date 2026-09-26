package io.github.pisces312.droidllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ProbeContext
import io.github.pisces312.droidllm.engineapi.SessionHandle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EngineChoice(
    val engine: LlmEngine,
    val displayName: String,
    val available: Boolean,
    val availability: Availability,
)

data class ModelChoice(
    val model: LocalModel,
    val displayName: String,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineSet: Set<@JvmSuppressWildcards LlmEngine>,
    private val modelStore: ModelPathStore,
    private val probe: io.github.pisces312.droidllm.common.device.DeviceProbe,
) : ViewModel() {

    private val _engines = MutableStateFlow<List<EngineChoice>>(emptyList())
    val engines: StateFlow<List<EngineChoice>> = _engines.asStateFlow()

    private val _selectedEngine = MutableStateFlow<EngineChoice?>(null)
    val selectedEngine: StateFlow<EngineChoice?> = _selectedEngine.asStateFlow()

    private val _models = MutableStateFlow<List<ModelChoice>>(emptyList())
    val models: StateFlow<List<ModelChoice>> = _models.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelChoice?>(null)
    val selectedModel: StateFlow<ModelChoice?> = _selectedModel.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("准备就绪")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _availability = MutableStateFlow("")
    val availability: StateFlow<String> = _availability.asStateFlow()

    private var session: SessionHandle? = null

    init {
        viewModelScope.launch {
            val ctx = probe.probe()
            val choices = engineSet.map { engine ->
                val av = engine.probe(ctx)
                EngineChoice(
                    engine = engine,
                    displayName = engine.displayName,
                    available = av is Availability.Available,
                    availability = av,
                )
            }.sortedBy { it.displayName }
            _engines.value = choices
            // Prefer FakeEngine for P0 so chat works out of the box.
            val preferred = choices.firstOrNull { it.available }
                ?: choices.firstOrNull()
            preferred?.let { selectEngine(it) }
        }
    }

    fun selectEngine(choice: EngineChoice) {
        _selectedEngine.value = choice
        _availability.value = when (val av = choice.availability) {
            is Availability.Available -> "可用"
            is Availability.MissingDependency -> "不可用：${av.detail}"
            is Availability.UnsupportedSoc -> "不支持的 SoC：${av.detail}"
            is Availability.ModelNotConfigured -> "未配置模型：${av.detail}"
            is Availability.InvalidModel -> "模型无效：${av.detail}"
        }
        viewModelScope.launch {
            unloadSession()
            loadModelsFor(choice.engine)
        }
    }

    fun selectModel(choice: ModelChoice) {
        _selectedModel.value = choice
        viewModelScope.launch {
            unloadSession()
            openSession(choice.model)
        }
    }

    private suspend fun loadModelsFor(engine: LlmEngine) {
        val all = modelStore.observeModels().first()
        val list = all.filter { it.engineId == engine.id }
            .map { ModelChoice(it, it.displayName) }
        _models.value = list
        // FakeEngine works without a model file; synthesize a dummy entry.
        if (list.isEmpty() && engine.id == io.github.pisces312.droidllm.engineapi.EngineId.FAKE) {
            val fake = LocalModel(
                id = "fake-default",
                engineId = io.github.pisces312.droidllm.engineapi.EngineId.FAKE,
                displayName = "Fake lorem model",
                location = io.github.pisces312.droidllm.engineapi.ModelLocation.AppPrivate("fake"),
            )
            val choice = ModelChoice(fake, fake.displayName)
            _models.value = listOf(choice)
            selectModel(choice)
        } else {
            _selectedModel.value = list.firstOrNull()
            list.firstOrNull()?.let { openSession(it.model) }
        }
    }

    private suspend fun openSession(model: LocalModel) {
        val engine = _selectedEngine.value?.engine ?: return
        if (_selectedEngine.value?.available != true) {
            _status.value = "引擎不可用，无法加载模型"
            return
        }
        _status.value = "加载中…"
        runCatching {
            val handle = engine.load(model, InferenceConfig())
            session = handle
            _status.value = "已加载 ${model.displayName}"
        }.onFailure {
            _status.value = "加载失败：${it.message}"
        }
    }

    private suspend fun unloadSession() {
        val handle = session ?: return
        runCatching { _selectedEngine.value?.engine?.unload(handle) }
        session = null
    }

    fun send(text: String) {
        val engine = _selectedEngine.value?.engine ?: return
        val handle = session ?: run {
            _status.value = "请先选择可用引擎和模型"
            return
        }
        val history = _messages.value + ChatMessage(ChatRole.USER, text)
        _messages.value = history
        _status.value = "生成中…"

        val sb = StringBuilder()
        val job = engine.generate(
            handle,
            GenerateRequest(history, InferenceConfig()),
        ) { event ->
            when (event) {
                is EngineEvent.Token -> {
                    sb.append(event.text)
                    _messages.value = history + ChatMessage(ChatRole.ASSISTANT, sb.toString())
                }
                is EngineEvent.Done -> {
                    val m = event.result.metrics
                    _status.value = "TTFT=${m.ttftMs}ms  decode=${m.decodeTps?.let { "%.1f".format(it) } ?: "-"} tps"
                }
                is EngineEvent.Error -> {
                    val msg = when (event.cause) {
                        is EngineException.Cancelled -> "已取消"
                        else -> "错误：${event.cause.message}"
                    }
                    _status.value = msg
                }
            }
        }
        // Keep reference so it is not GC'd mid-run (cancel via status later).
        @Suppress("UNUSED_VARIABLE")
        val activeJob = job
    }
}

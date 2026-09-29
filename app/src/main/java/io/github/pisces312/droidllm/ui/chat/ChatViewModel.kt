package io.github.pisces312.droidllm.ui.chat

import io.github.pisces312.droidllm.R

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.common.model.ModelParamsOverride
import io.github.pisces312.droidllm.common.model.ModelParamsStore
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.model.find
import io.github.pisces312.droidllm.common.settings.AppSettings
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.DEFAULT_SYSTEM_PROMPT
import io.github.pisces312.droidllm.engine.mnn.MnnEngine
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.GenerateJob
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.SessionHandle
import io.github.pisces312.droidllm.engineapi.ThinkingSupport
import io.github.pisces312.droidllm.engineapi.labelledName
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * Why [this] engine cannot run right now, or null when it can.
 *
 * Shared by the chat status line and the engine chips in the scope sheet, so an
 * unusable engine reads the same in both places (UI_DESIGN.md §6).
 */
fun EngineChoice.unavailableReason(context: Context): String? = when (val av = availability) {
    is Availability.Available -> null
    is Availability.MissingDependency -> context.getString(R.string.chat_unavailable_missing_dep, av.detail)
    is Availability.UnsupportedSoc -> context.getString(R.string.chat_unavailable_soc, av.detail)
    is Availability.ModelNotConfigured -> context.getString(R.string.chat_unavailable_no_model, av.detail)
    is Availability.InvalidModel -> context.getString(R.string.chat_unavailable_invalid_model, av.detail)
}

/**
 * Lifecycle of the chat session. A model is only resident in memory between
 * [READY] and the next [IDLE]; picking an engine or model always drops back to
 * [IDLE] so the previous model is released before the new one is started.
 */
enum class SessionState { IDLE, LOADING, READY, FAILED }

/** One chat turn, with every performance metric the engine reported attached to the assistant bubble. */
data class ChatUiMessage(
    val role: ChatRole,
    val content: String,
    /** Snapshot of the per-turn Thinking knob; display uses it to drop empty/off thinking blocks. */
    val thinkingEnabled: Boolean = true,
    val ttftMs: Long? = null,
    val prefillTps: Double? = null,
    val decodeTps: Double? = null,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val perTokenMsP50: Double? = null,
    val error: String? = null,
)

/**
 * The effective sampling / execution values for the currently selected
 * engine + model, plus the overlay that produced them.
 *
 * The constructor defaults only apply before an engine is picked (the state is
 * created before [ChatViewModel.init] resolves the engine list); once one is
 * selected every value comes from [effectiveSampling].
 *
 * There is deliberately **no app-wide sampling layer**. The chain is
 * `<per-model override> → <selected engine's EngineDefaults>`, and only
 * `systemPrompt` still has an app-level default (it is a prompt, not a sampling
 * knob — see `EngineDefaults`' KDoc).
 */
data class SamplingUiState(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val threads: Int = 4,
    val maxNewTokens: Int = 4096,
    val backend: Backend = Backend.AUTO,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * Reasoning on/off for the next turn (UI_REVIEW §3.2 模式 3).
     * Default OFF — gallery LLM_CHAT also ships thinking off; forcing
     * `enable_thinking=true` into LiteRT extraContext can make small models
     * emit an unclosed `众所周` stream instead of a normal reply.
     */
    val enableThinking: Boolean = false,
    /**
     * Overlay stored for this **engine + model** pair (`ENGINE:modelId`). A null
     * field falls back to the engine's own `EngineDefaults`; non-null fields are
     * marked "仅本模型" in the sampling sheet.
     */
    val override: ModelParamsOverride = ModelParamsOverride(),
) {
    /** Fields currently pinned to this model rather than the engine default. */
    val overriddenFields: Set<ParamsField>
        get() = buildSet {
            if (override.temperature != null) add(ParamsField.TEMPERATURE)
            if (override.topK != null) add(ParamsField.TOP_K)
            if (override.topP != null) add(ParamsField.TOP_P)
            if (override.threads != null) add(ParamsField.THREADS)
            if (override.maxNewTokens != null) add(ParamsField.MAX_NEW_TOKENS)
            if (override.backend != null) add(ParamsField.BACKEND)
            if (override.systemPrompt != null) add(ParamsField.SYSTEM_PROMPT)
        }

    val hasOverrides: Boolean get() = overriddenFields.isNotEmpty()
}

/** Sampling fields a per-model override can pin (drives the "仅本模型" markers). */
enum class ParamsField {
    TEMPERATURE,
    TOP_K,
    TOP_P,
    THREADS,
    MAX_NEW_TOKENS,
    BACKEND,
    SYSTEM_PROMPT,
}

/** Typed value for one overlay write; `null` value = unpin that field. */
sealed interface FieldValue {
    data class Num(val v: Int) : FieldValue
    data class Dec(val v: Float) : FieldValue
    data class BackendValue(val v: Backend) : FieldValue
    data class Text(val v: String) : FieldValue
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engineSet: Set<@JvmSuppressWildcards LlmEngine>,
    private val modelStore: ModelPathStore,
    private val modelParamsStore: ModelParamsStore,
    private val probe: io.github.pisces312.droidllm.common.device.DeviceProbe,
    private val sessionRegistry: io.github.pisces312.droidllm.common.bench.SessionRegistry,
    private val settingsStore: AppSettingsStore,
    @param:ApplicationContext private val appContext: Context,
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

    private val _status = MutableStateFlow(appContext.getString(R.string.chat_status_ready))
    val status: StateFlow<String> = _status.asStateFlow()

    private val _availability = MutableStateFlow("")
    val availability: StateFlow<String> = _availability.asStateFlow()

    private val _sampling = MutableStateFlow(SamplingUiState())
    val sampling: StateFlow<SamplingUiState> = _sampling.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /**
     * Whether the current engine+model can flip Thinking. Resolved after model
     * selection; false hides the composer chip (UI_REVIEW §3.2 模式 3).
     */
    private val _thinkingSupported = MutableStateFlow(false)
    val thinkingSupported: StateFlow<Boolean> = _thinkingSupported.asStateFlow()

    private var session: SessionHandle? = null
    private var sessionEngine: LlmEngine? = null
    private var activeJob: GenerateJob? = null
    /**
     * Monotonic id for the in-flight generate turn. Bumped on every send and
     * every stop so late engine callbacks (tokens that were already in flight
     * when Stop was tapped) are dropped instead of appending to the bubble.
     */
    @Volatile
    private var generateSeq = 0
    private var multiResidency = false
    /** Kept sessions when multi-model residency is on (DESIGN §3.3). */
    private val resident = LinkedHashMap<String, Pair<LlmEngine, SessionHandle>>()

    /** Latest `AppSettings` snapshot (systemPrompt / residency / theme). Holds no sampling knobs. */
    private var globalSettings = AppSettings()

    init {
        viewModelScope.launch {
            globalSettings = settingsStore.current()
            multiResidency = globalSettings.multiModelResidency
            _sampling.value = effectiveSampling()
            settingsStore.observe().collect { s ->
                globalSettings = s
                multiResidency = s.multiModelResidency
                if (!_generating.value) refreshSampling()
            }
        }
        // A settings import rewrites the overlay for the selected model too.
        viewModelScope.launch {
            modelParamsStore.observe().collect { map ->
                overridesByKey = map
                if (!_generating.value) refreshSampling()
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

    /**
     * Effective values for the selected engine + model: the overlay stored for
     * that exact pair, then the engine's own `EngineDefaults`.
     *
     * There is deliberately **no app-wide sampling layer** — an app-level
     * `0.7 / 40` inherited by a model family that needs `1.0 / 64` is exactly how
     * the LiteRT greeting loop reached a real device (`docs/litert.md` §3).
     *
     * Reading the overlay from the in-memory snapshot is not possible
     * synchronously, so callers that need the *stored* value must go through
     * [loadOverrideForSelected]; this uses the last observed snapshot.
     */
    private fun effectiveSampling(): SamplingUiState {
        val model = _selectedModel.value?.model
        val engine = _selectedEngine.value?.engine
        val current = _sampling.value
        val o = if (engine == null || model == null) {
            ModelParamsOverride()
        } else {
            overridesByKey.find(engine.id, model.id) ?: ModelParamsOverride()
        }
        val d = engine?.defaults
        return SamplingUiState(
            temperature = o.temperature ?: d?.temperature ?: current.temperature,
            topK = o.topK ?: d?.topK ?: current.topK,
            topP = o.topP ?: d?.topP ?: current.topP,
            threads = o.threads ?: d?.threads ?: current.threads,
            maxNewTokens = o.maxNewTokens ?: d?.maxNewTokens ?: current.maxNewTokens,
            backend = ModelParamsOverride.backendOf(o.backend) ?: d?.backend ?: current.backend,
            systemPrompt = o.systemPrompt ?: globalSettings.systemPrompt,
            // Thinking is a per-turn knob, not a settings default; keep the current
            // session value when settings push a new snapshot.
            enableThinking = current.enableThinking,
            override = o,
        )
    }

    /** Latest overlay snapshot, keyed by `ENGINE:modelId`; refreshed by the collector in [init]. */
    private var overridesByKey: Map<String, ModelParamsOverride> = emptyMap()

    private fun refreshSampling() {
        val next = effectiveSampling()
        _sampling.value = if (next == _sampling.value) _sampling.value else next
    }

    /** Picking an engine releases any running model; the new one waits for [startModel]. */
    fun selectEngine(choice: EngineChoice) {
        viewModelScope.launch {
            releaseSession()
            _selectedEngine.value = choice
            _availability.value = describeAvailability(choice)
            loadModelsFor(choice.engine)
        }
    }

    /** Picking a model releases any running model; the new one waits for [startModel]. */
    fun selectModel(choice: ModelChoice) {
        viewModelScope.launch {
            releaseSession()
            _selectedModel.value = choice
            markIdle()
            refreshThinkingSupport(choice)
            // Swap in this model's overlay (or fall back to the engine's own defaults).
            refreshSampling()
        }
    }

    /** Per-turn Thinking switch. No-op when the model has no thinking mode. */
    fun setThinking(enabled: Boolean) {
        _sampling.value = _sampling.value.copy(enableThinking = enabled)
    }

    private fun refreshThinkingSupport(choice: ModelChoice?) {
        viewModelScope.launch {
            val engine = _selectedEngine.value?.engine
            val model = choice?.model
            if (engine == null || model == null) {
                _thinkingSupported.value = false
                return@launch
            }
            val templateFlag = withContext(Dispatchers.IO) {
                if (engine.id == EngineId.MNN) {
                    val path = when (val loc = model.location) {
                        is ModelLocation.FilePath -> loc.path
                        is ModelLocation.AppPrivate -> loc.relativePath
                        is ModelLocation.SafUri -> null
                    }
                    path?.let { MnnEngine.templateHasEnableThinking(it) }
                } else {
                    null
                }
            }
            _thinkingSupported.value = ThinkingSupport.supports(model, templateFlag)
        }
    }

    /** Load the selected model. Any previously running model is released first. */
    fun startModel() {
        val model = _selectedModel.value
        if (model == null) {
            _status.value = appContext.getString(R.string.chat_status_pick_first)
            return
        }
        if (_sessionState.value == SessionState.LOADING) return
        viewModelScope.launch {
            stopGenerate()
            unloadSession()
            openSession(model.model)
        }
    }

    /** Release the running model and stop generating. */
    fun stopModel() {
        viewModelScope.launch {
            releaseSession()
            markIdle()
        }
    }

    private suspend fun releaseSession() {
        stopGenerate()
        unloadSession()
    }

    private fun markIdle() {
        _sessionState.value = SessionState.IDLE
        val name = _selectedModel.value?.displayName
        _status.value = if (name == null) {
            appContext.getString(R.string.chat_status_idle_no_model)
        } else {
            appContext.getString(R.string.chat_status_idle, name)
        }
    }

    fun updateSampling(transform: (SamplingUiState) -> SamplingUiState) {
        _sampling.value = transform(_sampling.value)
    }

    /**
     * Pin one sampling field to the selected engine + model (overlay write), or
     * unpin it with `value = null` so the field falls back to the engine's own
     * defaults.
     *
     * Callers edit the *effective* value they see on screen: pinning with a value
     * persists that value, editing a pinned field overwrites it, and unpinning
     * always falls back to the engine default.
     */
    fun setModelOverride(field: ParamsField, value: FieldValue?) {
        val modelId = _selectedModel.value?.model?.id ?: return
        val engineId = _selectedEngine.value?.engine?.id ?: return
        val cur = _sampling.value.override
        val next = when (field) {
            ParamsField.TEMPERATURE -> cur.copy(temperature = (value as? FieldValue.Dec)?.v)
            ParamsField.TOP_K -> cur.copy(topK = (value as? FieldValue.Num)?.v)
            ParamsField.TOP_P -> cur.copy(topP = (value as? FieldValue.Dec)?.v)
            ParamsField.THREADS -> cur.copy(threads = (value as? FieldValue.Num)?.v)
            ParamsField.MAX_NEW_TOKENS -> cur.copy(maxNewTokens = (value as? FieldValue.Num)?.v)
            ParamsField.BACKEND -> cur.copy(backend = (value as? FieldValue.BackendValue)?.v?.name)
            ParamsField.SYSTEM_PROMPT -> cur.copy(systemPrompt = (value as? FieldValue.Text)?.v)
        }
        viewModelScope.launch { modelParamsStore.set(engineId, modelId, next) }
    }

    /**
     * Drop every override pinned to this engine + model; it reverts to the
     * engine's own defaults. Overrides pinned to the same model under a
     * different engine are untouched — see [modelParamsKey].
     */
    fun clearModelOverride() {
        val modelId = _selectedModel.value?.model?.id ?: return
        val engineId = _selectedEngine.value?.engine?.id ?: return
        viewModelScope.launch { modelParamsStore.set(engineId, modelId, null) }
    }

    /**
     * Engines without a native `enable_thinking` flag still honor the Qwen3
     * `/think` / `/no_think` turn suffix. MNN takes the jinja route instead
     * (see `MnnEngine.buildConfigJson`) so the tag is not doubled. LiteRT
     * receives `enable_thinking` via `extraContext` (gallery behavior).
     */
    private fun applyThinkingTurn(
        text: String,
        config: InferenceConfig,
        engine: LlmEngine,
    ): String {
        if (engine.id == EngineId.MNN || engine.id == EngineId.LITERT) return text
        if (!ThinkingSupport.byName(_selectedModel.value?.displayName.orEmpty())) return text
        val tag = if (config.enableThinking) "/think" else "/no_think"
        return if (text.trimEnd().endsWith(tag)) text else text.trimEnd() + "\n" + tag
    }

    fun newSession() {
        viewModelScope.launch {
            stopGenerate()
            val handle = session
            val engine = sessionEngine
            if (_sessionState.value == SessionState.READY && handle != null && engine != null) {
                runCatching { withContext(Dispatchers.IO) { engine.reset(handle) } }
                _status.value = appContext.getString(R.string.chat_status_new_session)
            } else {
                _status.value = appContext.getString(R.string.chat_status_cleared)
            }
            _messages.value = emptyList()
        }
    }

    fun stopGenerate() {
        val wasGenerating = _generating.value
        generateSeq++
        activeJob?.cancel()
        activeJob = null
        _generating.value = false
        if (wasGenerating) {
            _status.value = appContext.getString(R.string.chat_status_stopped)
        }
    }

    private fun describeAvailability(choice: EngineChoice): String =
        choice.unavailableReason(appContext) ?: appContext.getString(R.string.engine_available)

    private suspend fun loadModelsFor(engine: LlmEngine) {
        val all = modelStore.observeModels().first()
        reconcileModels(engine, all)
    }

    /**
     * Rebuild the model picker for [engine] from [all] stored models. Falls
     * back to the first entry when nothing valid is selected — e.g. right
     * after a download. An engine without models leaves an empty picker and
     * an IDLE session; starting then reports "pick a model first".
     */
    private fun reconcileModels(engine: LlmEngine, all: List<LocalModel>) {
        val list = all.filter { it.engineId == engine.id }
            .map { ModelChoice(it, it.displayName) }
        _models.value = list
        val current = _selectedModel.value
        if (current == null || list.none { it.model.id == current.model.id }) {
            val next = list.firstOrNull()
            _selectedModel.value = next
            markIdle()
            refreshThinkingSupport(next)
            refreshSampling()
        } else {
            refreshThinkingSupport(current)
        }
    }

    private suspend fun openSession(model: LocalModel) {
        val engine = _selectedEngine.value?.engine ?: return
        if (_selectedEngine.value?.available != true) {
            _sessionState.value = SessionState.FAILED
            _status.value = appContext.getString(R.string.chat_status_engine_unavailable)
            return
        }
        val key = "${engine.id}:${model.id}"
        if (multiResidency) {
            resident[key]?.let { (eng, handle) ->
                session = handle
                sessionEngine = eng
                _sessionState.value = SessionState.READY
                _status.value = appContext.getString(R.string.chat_status_switched_resident, model.displayName)
                return
            }
        }
        _sessionState.value = SessionState.LOADING
        _status.value = appContext.getString(R.string.common_loading)
        runCatching {
            // Adapters call straight into native load()/unload(), which run for
            // seconds. Keep them off the main thread so the LOADING state can
            // actually be rendered instead of freezing the frame.
            val handle = withContext(Dispatchers.IO) {
                engine.load(model, _sampling.value.toConfig())
            }
            session = handle
            sessionEngine = engine
            sessionRegistry.register(engine, handle)
            if (multiResidency) resident[key] = engine to handle
            _sessionState.value = SessionState.READY
            _status.value = appContext.getString(R.string.chat_status_loaded, model.displayName)
        }.onFailure {
            val err = it.message ?: it.javaClass.simpleName
            session = null
            sessionEngine = null
            _sessionState.value = SessionState.FAILED
            _status.value = appContext.getString(R.string.chat_status_load_failed, err)
        }
    }

    private suspend fun unloadSession() {
        if (multiResidency) return
        val handle = session ?: return
        val engine = sessionEngine ?: _selectedEngine.value?.engine
        engine?.let { runCatching { withContext(Dispatchers.IO) { it.unload(handle) } } }
        sessionRegistry.unregister(handle)
        session = null
        sessionEngine = null
    }

    fun send(text: String) {
        if (_sessionState.value != SessionState.READY) {
            _status.value = appContext.getString(R.string.chat_status_not_started)
            return
        }
        val engine = _selectedEngine.value?.engine ?: return
        val handle = session ?: run {
            _sessionState.value = SessionState.IDLE
            markIdle()
            return
        }
        if (_generating.value) return
        val config = _sampling.value.toConfig()
        // The system turn is materialised here instead of being stored in the
        // transcript: chat templates read the system instruction from
        // messages[0], while the bubble list stays user/assistant only.
        val userText = applyThinkingTurn(text, config, engine)
        val history = buildList {
            config.systemPrompt?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            addAll(_messages.value.map { ChatMessage(it.role, it.content) })
            add(ChatMessage(ChatRole.USER, userText))
        }
        _messages.value = _messages.value + ChatUiMessage(ChatRole.USER, text)
        _status.value = appContext.getString(R.string.chat_status_generating)
        _generating.value = true
        val seq = ++generateSeq

        val sb = StringBuilder()
        val startedAt = System.nanoTime()
        var firstTokenNs = 0L
        var tokenCount = 0

        val job = engine.generate(handle, GenerateRequest(history, config)) { event ->
            if (seq != generateSeq) return@generate
            when (event) {
                is EngineEvent.Token -> {
                    if (tokenCount == 0) firstTokenNs = System.nanoTime()
                    tokenCount++
                    sb.append(event.text)
                    val last = _messages.value.lastOrNull()
                    if (last?.role == ChatRole.ASSISTANT && last.error == null) {
                        _messages.value = _messages.value.dropLast(1) +
                            ChatUiMessage(ChatRole.ASSISTANT, sb.toString(), config.enableThinking)
                    } else {
                        _messages.value = _messages.value +
                            ChatUiMessage(ChatRole.ASSISTANT, sb.toString(), config.enableThinking)
                    }
                }
                is EngineEvent.Done -> {
                    val m = event.result.metrics
                    val ttft = m.ttftMs ?: if (firstTokenNs > 0) {
                        (firstTokenNs - startedAt) / 1_000_000
                    } else null
                    // Empty thinking blocks still count as "no output" for the bubble.
                    val emptyReply =
                        ThinkingDisplay.forDisplay(sb.toString(), config.enableThinking).isBlank()
                    val hitTokenCap = m.generatedTokens >= config.maxNewTokens && !emptyReply
                    val content = buildString {
                        append(if (emptyReply) appContext.getString(R.string.chat_empty_reply) else sb.toString())
                        if (hitTokenCap) {
                            append(appContext.getString(R.string.chat_token_cap_note, config.maxNewTokens))
                        }
                    }
                    val bubble = ChatUiMessage(
                        role = ChatRole.ASSISTANT,
                        content = content,
                        thinkingEnabled = config.enableThinking,
                        ttftMs = ttft,
                        prefillTps = m.prefillTps,
                        decodeTps = m.decodeTps,
                        promptTokens = m.promptTokens,
                        generatedTokens = m.generatedTokens,
                        perTokenMsP50 = m.perTokenMsP50,
                    )
                    val last = _messages.value.lastOrNull()
                    if (last?.role == ChatRole.ASSISTANT) {
                        _messages.value = _messages.value.dropLast(1) + bubble
                    } else if (emptyReply) {
                        // Nothing was streamed (the model stopped before emitting
                        // any text). Add a bubble so the turn is not silently blank.
                        _messages.value = _messages.value + bubble
                    }
                    _status.value = buildString {
                        append(
                            appContext.getString(
                                if (emptyReply) R.string.chat_done_no_output else R.string.common_done,
                            )
                        )
                        if (hitTokenCap) append(appContext.getString(R.string.chat_done_token_cap))
                        ttft?.let { append(" · TTFT ${it}ms") }
                        m.prefillTps?.let { append(" · prefill %.1f tok/s".format(it)) }
                        m.decodeTps?.let { append(" · decode %.1f tok/s".format(it)) }
                        if (m.promptTokens > 0 || m.generatedTokens > 0) {
                            append(" · ${m.promptTokens}→${m.generatedTokens} tok")
                        }
                        if (m.warnings.isNotEmpty()) append(" · ").append(m.warnings.joinToString("；"))
                    }
                    _generating.value = false
                    activeJob = null
                }
                is EngineEvent.Error -> {
                    val cause = event.cause
                    val msg = when (cause) {
                        is EngineException.Cancelled -> appContext.getString(R.string.chat_status_stopped)
                        is EngineException.GenerateFailed ->
                            appContext.getString(
                                R.string.chat_error_generate_failed,
                                cause.message ?: appContext.getString(R.string.common_unknown),
                            )
                        else -> appContext.getString(R.string.chat_error_generic, cause.message ?: cause.javaClass.simpleName)
                    }
                    val content = sb.toString()
                    val last = _messages.value.lastOrNull()
                    if (last?.role == ChatRole.ASSISTANT) {
                        _messages.value = _messages.value.dropLast(1) +
                            ChatUiMessage(ChatRole.ASSISTANT, content, config.enableThinking, error = msg)
                    } else {
                        _messages.value = _messages.value +
                            ChatUiMessage(ChatRole.ASSISTANT, content, config.enableThinking, error = msg)
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
        systemPrompt = systemPrompt.takeIf { it.isNotBlank() },
        enableThinking = enableThinking,
    )
}

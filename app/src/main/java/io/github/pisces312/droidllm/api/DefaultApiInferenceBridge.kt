package io.github.pisces312.droidllm.api

import io.github.pisces312.droidllm.apiserver.ApiChatParams
import io.github.pisces312.droidllm.apiserver.ApiGenerateResult
import io.github.pisces312.droidllm.apiserver.ApiInferenceBridge
import io.github.pisces312.droidllm.apiserver.ApiModelInfo
import io.github.pisces312.droidllm.common.bench.SessionRegistry
import io.github.pisces312.droidllm.common.model.ModelParamsOverride
import io.github.pisces312.droidllm.common.model.ModelParamsStore
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.GenerateJob
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.SessionHandle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [ApiInferenceBridge] backed by [LlmEngine]. Owns at most one API session
 * (single-model residency). Chat UI sessions are separate; avoid running UI
 * generation and API traffic at the same time (documented in Settings).
 */
@Singleton
class DefaultApiInferenceBridge @Inject constructor(
    private val engines: Set<@JvmSuppressWildcards LlmEngine>,
    private val modelStore: ModelPathStore,
    private val modelParamsStore: ModelParamsStore,
    private val settingsStore: AppSettingsStore,
    private val sessionRegistry: SessionRegistry,
) : ApiInferenceBridge {

    private val sessionMutex = Mutex()
    private val generateMutex = Mutex()

    private var session: SessionHandle? = null
    private var sessionEngine: LlmEngine? = null
    private var sessionModelId: String? = null
    private var activeJob: GenerateJob? = null

    override suspend fun listModels(): List<ApiModelInfo> =
        modelStore.listModels().map {
            ApiModelInfo(
                id = it.id,
                displayName = it.displayName,
                engineId = it.engineId.name,
            )
        }

    override suspend fun generate(
        params: ApiChatParams,
        onToken: (String) -> Boolean,
    ): ApiGenerateResult = generateMutex.withLock {
        val model = resolveModel(params.model)
        val handle = ensureSession(model)

        val activeEngine = sessionEngine ?: throw IllegalStateException("no active session engine")
        // [baseConfig] already applied the engine defaults and the per-model pin, so
        // only the request body goes on top of it.
        val base = baseConfig(activeEngine, model)
        val config = base.copy(
            maxNewTokens = params.maxTokens ?: base.maxNewTokens,
            temperature = params.temperature ?: base.temperature,
            topP = params.topP ?: base.topP,
        )

        val history = buildList {
            // Client-supplied system messages win; otherwise fall back to the
            // app default system prompt (engines expect system first).
            val hasSystem = params.messages.any { it.role.equals("system", ignoreCase = true) }
            if (!hasSystem) {
                config.systemPrompt?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            }
            params.messages.forEach { m ->
                val role = when (m.role.lowercase()) {
                    "system" -> ChatRole.SYSTEM
                    "assistant" -> ChatRole.ASSISTANT
                    else -> ChatRole.USER
                }
                add(ChatMessage(role, m.content))
            }
        }
        val configWithSystem = if (params.messages.any { it.role.equals("system", ignoreCase = true) }) {
            config.copy(systemPrompt = null)
        } else {
            config
        }

        val sb = StringBuilder()
        val done = CompletableDeferred<GenerateResultData>()
        var jobRef: GenerateJob? = null
        val job = activeEngine.generate(
            handle,
            GenerateRequest(history, configWithSystem),
        ) { event ->
            when (event) {
                is EngineEvent.Token -> {
                    sb.append(event.text)
                    if (!onToken(event.text)) {
                        // Client disconnected; cancel even if activeJob not yet set.
                        jobRef?.cancel()
                        activeJob?.cancel()
                    }
                }
                is EngineEvent.Done -> {
                    done.complete(
                        GenerateResultData(
                            text = sb.toString(),
                            promptTokens = event.result.promptTokens,
                            generatedTokens = event.result.generatedTokens,
                        ),
                    )
                }
                is EngineEvent.Error -> {
                    done.completeExceptionally(event.cause)
                }
            }
        }
        jobRef = job
        activeJob = job
        try {
            val result = done.await()
            ApiGenerateResult(
                modelId = model.id,
                text = result.text,
                promptTokens = result.promptTokens,
                generatedTokens = result.generatedTokens,
            )
        } finally {
            activeJob = null
        }
    }

    override fun cancelActive() {
        activeJob?.cancel()
    }

    /** Release the API session (called when the foreground service stops). */
    suspend fun release() {
        generateMutex.withLock {
            activeJob?.cancel()
            activeJob = null
        }
        sessionMutex.withLock {
            val handle = session ?: return
            val engine = sessionEngine
            session = null
            sessionEngine = null
            sessionModelId = null
            withContext(Dispatchers.IO) {
                runCatching { engine?.unload(handle) }
            }
            sessionRegistry.unregister(handle)
        }
    }

    private suspend fun resolveModel(requested: String?): LocalModel {
        val all = modelStore.listModels()
        if (all.isEmpty()) throw IllegalStateException("no models registered")
        if (requested.isNullOrBlank() || requested == "default") return all.first()
        return all.firstOrNull { it.id == requested }
            ?: throw IllegalArgumentException("model not found: $requested")
    }

    private suspend fun ensureSession(model: LocalModel): SessionHandle {
        sessionMutex.withLock {
            val existing = session
            if (existing != null && sessionModelId == model.id && !existing.isClosed) {
                return existing
            }
            existing?.let { handle ->
                val eng = sessionEngine
                withContext(Dispatchers.IO) { runCatching { eng?.unload(handle) } }
                sessionRegistry.unregister(handle)
                session = null
                sessionEngine = null
                sessionModelId = null
            }
            val engine = engines.firstOrNull { it.id == model.engineId }
                ?: throw IllegalStateException("engine not available: ${model.engineId}")
            val handle = withContext(Dispatchers.IO) {
                engine.load(model, baseConfig(engine, model))
            }
            session = handle
            sessionEngine = engine
            sessionModelId = model.id
            sessionRegistry.register(engine, handle)
            return handle
        }
    }

    /**
     * The config a session is opened with: the **engine's own defaults**, with the
     * overlay pinned to this engine + model applied on top — the same chain the
     * chat UI uses. There is no app-wide sampling layer.
     *
     * The backend must be right *here*: LiteRT-LM builds its delegate inside
     * `load()`, so a later generate-time value cannot correct it.
     *
     * The API server applies request fields on top of this by itself.
     */
    private suspend fun baseConfig(engine: LlmEngine, model: LocalModel): InferenceConfig {
        val d = engine.defaults
        val o = modelParamsStore.get(engine.id, model.id) ?: ModelParamsOverride()
        val settings = settingsStore.current()
        return InferenceConfig(
            maxNewTokens = o.maxNewTokens ?: d.maxNewTokens,
            temperature = o.temperature ?: d.temperature,
            topK = o.topK ?: d.topK,
            topP = o.topP ?: d.topP,
            threads = o.threads ?: d.threads,
            backend = ModelParamsOverride.backendOf(o.backend) ?: d.backend,
            // The one app-level value left: a prompt, not a sampling knob.
            systemPrompt = o.systemPrompt ?: settings.systemPrompt.takeIf { it.isNotBlank() },
        )
    }

    private data class GenerateResultData(
        val text: String,
        val promptTokens: Int,
        val generatedTokens: Int,
    )
}

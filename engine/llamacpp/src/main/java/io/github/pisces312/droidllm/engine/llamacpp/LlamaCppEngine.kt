package io.github.pisces312.droidllm.engine.llamacpp

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.pisces312.droidllm.chattemplate.ChatTemplate
import io.github.pisces312.droidllm.common.metrics.MetricsCollector
import io.github.pisces312.droidllm.common.metrics.RssReader
import io.github.pisces312.droidllm.engine.llamacpp.BuildConfig
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.EngineMetrics
import io.github.pisces312.droidllm.engineapi.EngineVersion
import io.github.pisces312.droidllm.engineapi.GenerateJob
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.GenerateResult
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.ProbeContext
import io.github.pisces312.droidllm.engineapi.SessionHandle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * llama.cpp adapter. CPU backend only in P1 (GGML OpenCL not linked).
 * Contract: Backend values other than CPU/AUTO are rejected; InferenceConfig
 * fields are applied via sampler chain; generate/unload mutually exclusive.
 */
@Singleton
class LlamaCppEngine @Inject constructor() : LlmEngine {

    override val id: EngineId = EngineId.LLAMACPP
    override val displayName: String get() = id.displayName
    override val version: EngineVersion = EngineVersion(
        version = BuildConfig.ENGINE_VERSION.takeIf { it.isNotBlank() },
        commit = BuildConfig.ENGINE_COMMIT.takeIf { it.isNotBlank() },
    )

    private class LlamaSession(
        override val modelId: String,
        val handle: Long,
        val config: InferenceConfig,
        val loadMs: Long,
        val rssMbLoad: Long?,
        val modelPath: String,
    ) : SessionHandle {
        override val engineId: EngineId = EngineId.LLAMACPP
        val closed = AtomicBoolean(false)
        val generating = AtomicBoolean(false)

        @Volatile
        var metrics: EngineMetrics? = null

        override val isClosed: Boolean get() = closed.get()
    }

    private class LlamaJob(private val job: Job) : GenerateJob {
        override fun cancel() = job.cancel()
        override val isActive: Boolean get() = job.isActive
    }

    @Volatile
    private var nativeReady: Boolean? = null

    private fun ensureNative(): Boolean {
        nativeReady?.let { return it }
        return try {
            LlamaCppNative.nativeInit()
            nativeReady = true
            true
        } catch (_: Throwable) {
            nativeReady = false
            false
        }
    }

    override suspend fun probe(probeContext: ProbeContext): Availability {
        if (!ensureNative()) {
            return Availability.MissingDependency("libllamacpp_chat_jni.so failed to load")
        }
        return Availability.Available
    }

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle {
        if (!ensureNative()) {
            throw EngineException.LoadFailed("llama.cpp native library unavailable")
        }
        val path = resolveModelPath(model)
        if (!File(path).isFile) {
            throw EngineException.LoadFailed("model file not found: $path")
        }
        mapBackend(config.backend)

        val start = System.nanoTime()
        val rssBefore = RssReader.rssMb()
        val handle = LlamaCppNative.nativeLoad(path, nCtx = N_CTX, nThreads = config.threads)
        if (handle == 0L) {
            throw EngineException.LoadFailed("llama_model_load_from_file failed for $path")
        }
        LlamaCppNative.nativeResetSampler(
            handle,
            topK = config.topK,
            topP = config.topP,
            temp = config.temperature,
            seed = config.seed ?: 0L,
        )
        val loadMs = (System.nanoTime() - start) / 1_000_000
        val rssAfter = RssReader.rssMb()
        val session = LlamaSession(
            modelId = model.id,
            handle = handle,
            config = config,
            loadMs = loadMs,
            rssMbLoad = rssAfter,
            modelPath = path,
        )
        session.metrics = EngineMetrics(
            loadMs = loadMs,
            rssMbLoad = rssAfter,
            rssMbPeak = rssAfter,
            effectiveConfig = config,
            warnings = warningsFor(config, rssBefore),
        )
        return session
    }

    override fun generate(
        handle: SessionHandle,
        request: GenerateRequest,
        onEvent: (EngineEvent) -> Unit,
    ): GenerateJob {
        val session = handle as? LlamaSession
            ?: throw EngineException.InvalidState("unknown session handle")
        if (session.isClosed) {
            throw EngineException.InvalidState("session already closed")
        }
        if (!session.generating.compareAndSet(false, true)) {
            throw EngineException.InvalidState("generate already running on this session")
        }
        mapBackend(request.config.backend)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            val collector = MetricsCollector()
            val rssPeakRef = longArrayOf(RssReader.rssMb() ?: 0L)
            try {
                LlamaCppNative.nativeResetSampler(
                    session.handle,
                    topK = request.config.topK,
                    topP = request.config.topP,
                    temp = request.config.temperature,
                    seed = request.config.seed ?: 0L,
                )
                val prompt = try {
                    LlamaCppNative.nativeApplyChatTemplate(
                        session.handle,
                        flattenMessages(request.messages),
                    )
                } catch (_: Throwable) {
                    ChatTemplate.format(request.messages.toPairs())
                }
                // TTFT starts at generate() emission and includes prefill (contract).
                collector.onGenerateStart(promptTokens = 0)
                val promptTokens = LlamaCppNative.nativePrefill(
                    session.handle,
                    prompt,
                    request.config.maxNewTokens,
                )
                if (promptTokens < 0) {
                    throw EngineException.GenerateFailed("prefill failed")
                }
                collector.onPromptTokenCount(promptTokens)

                val text = StringBuilder()
                var generated = 0
                while (generated < request.config.maxNewTokens) {
                    ensureActive()
                    val piece = LlamaCppNative.nativeNextToken(session.handle) ?: break
                    if (piece.isNotEmpty()) {
                        collector.onToken()
                        text.append(piece)
                        onEvent(EngineEvent.Token(piece, generated))
                        generated++
                    }
                }
                // Sampled once per turn: a per-token /proc/self/status read sits on
                // the decode hot path and inflates the reported decode latency.
                RssReader.rssMb()?.let { if (it > rssPeakRef[0]) rssPeakRef[0] = it }

                val metrics = collector.build(
                    generatedTokens = generated,
                    loadMs = session.loadMs,
                    rssMbLoad = session.rssMbLoad,
                    rssMbPeak = rssPeakRef[0],
                    effectiveConfig = request.config,
                    warnings = warningsFor(request.config),
                )
                session.metrics = metrics
                onEvent(
                    EngineEvent.Done(
                        GenerateResult(
                            text = text.toString(),
                            promptTokens = promptTokens,
                            generatedTokens = generated,
                            metrics = metrics,
                        ),
                    ),
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                onEvent(EngineEvent.Error(EngineException.Cancelled()))
            } catch (t: Throwable) {
                onEvent(
                    EngineEvent.Error(
                        if (t is EngineException) t
                        else EngineException.GenerateFailed(t.message ?: "llama.cpp generate failed", t),
                    ),
                )
            } finally {
                session.generating.set(false)
                scope.cancel()
            }
        }
        return LlamaJob(job)
    }

    override suspend fun reset(handle: SessionHandle) {
        val session = handle as? LlamaSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("cannot reset while generating")
        }
        LlamaCppNative.nativeClearKv(session.handle)
    }

    override suspend fun unload(handle: SessionHandle) {
        val session = handle as? LlamaSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("unload called while generating; cancel first")
        }
        if (session.closed.compareAndSet(false, true)) {
            LlamaCppNative.nativeFree(session.handle)
            session.metrics = null
        }
    }

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? =
        (handle as? LlamaSession)?.metrics

    private fun resolveModelPath(model: LocalModel): String {
        return when (val loc = model.location) {
            is ModelLocation.FilePath -> loc.path
            is ModelLocation.AppPrivate -> loc.relativePath
            is ModelLocation.SafUri ->
                throw EngineException.LoadFailed("SAF uri not supported for llama.cpp yet; use absolute path")
        }
    }

    private fun mapBackend(backend: Backend) {
        when (backend) {
            Backend.CPU, Backend.AUTO -> Unit
            else -> throw EngineException.UnsupportedBackend(
                "llama.cpp P1 supports CPU only; got $backend",
            )
        }
    }

    private fun warningsFor(config: InferenceConfig, rssBefore: Long? = null): List<String> {
        val w = mutableListOf("llama.cpp backend=CPU (OpenCL not linked in P1)")
        if (config.backend == Backend.AUTO) {
            w += "Backend.AUTO resolved to CPU"
        }
        if (config.systemPrompt != null) {
            // system prompt is folded into the formatted messages; no separate warning needed
        }
        rssBefore?.let { w += "rss baseline before load: ${it}MB" }
        return w
    }

    private fun List<ChatMessage>.toPairs(): List<Pair<String, String>> =
        map { it.role.toRoleString() to it.content }

    private fun flattenMessages(messages: List<ChatMessage>): Array<String> {
        val out = ArrayList<String>(messages.size * 2)
        for (m in messages) {
            out += m.role.toRoleString()
            out += m.content
        }
        return out.toTypedArray()
    }

    private fun ChatRole.toRoleString(): String = when (this) {
        ChatRole.SYSTEM -> "system"
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
    }

    private companion object {
        const val N_CTX = 2048
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LlamaCppEngineModule {
    @Binds
    @IntoSet
    abstract fun bindEngine(impl: LlamaCppEngine): LlmEngine
}

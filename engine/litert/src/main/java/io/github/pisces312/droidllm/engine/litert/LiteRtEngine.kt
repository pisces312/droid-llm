package io.github.pisces312.droidllm.engine.litert

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.pisces312.droidllm.engine.litert.BuildConfig
import io.github.pisces312.droidllm.common.metrics.MetricsCollector
import io.github.pisces312.droidllm.common.metrics.RssReader
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.Backend as AppBackend
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * LiteRT-LM adapter (pure Kotlin + litertlm-android AAR).
 *
 * Backend: CPU / GPU(delegate) / NPU. OPENCL is rejected (LiteRT uses GPU delegate).
 * LiteRT applies its own chat template internally via Conversation.
 */
@Singleton
class LiteRtEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : LlmEngine {

    override val id: EngineId = EngineId.LITERT
    override val displayName: String get() = id.displayName
    override val version: EngineVersion = EngineVersion(
        version = BuildConfig.ENGINE_VERSION.takeIf { it.isNotBlank() },
        commit = BuildConfig.ENGINE_COMMIT.takeIf { it.isNotBlank() },
    )

    private class LiteRtSession(
        override val modelId: String,
        val engine: Engine,
        @Volatile var conversation: Conversation?,
        val config: InferenceConfig,
        val loadMs: Long,
        val rssMbLoad: Long?,
        val modelPath: String,
    ) : SessionHandle {
        override val engineId: EngineId = EngineId.LITERT
        val closed = AtomicBoolean(false)
        val generating = AtomicBoolean(false)

        @Volatile
        var metrics: EngineMetrics? = null

        override val isClosed: Boolean get() = closed.get()
    }

    private class LiteRtJob(private val job: Job) : GenerateJob {
        override fun cancel() = job.cancel()
        override val isActive: Boolean get() = job.isActive
    }

    override suspend fun probe(probeContext: ProbeContext): Availability {
        return try {
            // Touch the AAR class to surface missing native deps early.
            Class.forName("com.google.ai.edge.litertlm.Engine")
            Availability.Available
        } catch (t: Throwable) {
            Availability.MissingDependency("litertlm-android failed to load: ${t.message}")
        }
    }

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle {
        val path = resolveModelPath(model)
        val file = File(path)
        if (!file.isFile) {
            throw EngineException.LoadFailed("LiteRT model file not found: $path")
        }
        val lower = file.name.lowercase()
        if (!lower.endsWith(".litertlm") && !lower.endsWith(".task")) {
            throw EngineException.LoadFailed("expect .litertlm or .task, got ${file.name}")
        }

        val backend = mapBackend(config.backend)
        val start = System.nanoTime()
        val rssBefore = RssReader.rssMb()
        val engineConfig = EngineConfig(
            modelPath = path,
            backend = backend,
            maxNumTokens = config.maxNewTokens,
            cacheDir = appContext.getExternalFilesDir(null)?.absolutePath,
        )
        val engine = try {
            Engine(engineConfig).also { it.initialize() }
        } catch (t: Throwable) {
            throw EngineException.LoadFailed("LiteRT engine init failed: ${t.message}", t)
        }
        val conversation = try {
            createConversation(engine, config, emptyList())
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw EngineException.LoadFailed("LiteRT conversation create failed: ${t.message}", t)
        }
        val loadMs = (System.nanoTime() - start) / 1_000_000
        val rssAfter = RssReader.rssMb()
        val session = LiteRtSession(
            modelId = model.id,
            engine = engine,
            conversation = conversation,
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
        val session = handle as? LiteRtSession
            ?: throw EngineException.InvalidState("unknown session handle")
        if (session.isClosed) {
            throw EngineException.InvalidState("session already closed")
        }
        if (!session.generating.compareAndSet(false, true)) {
            throw EngineException.InvalidState("generate already running on this session")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            val collector = MetricsCollector()
            val rssPeakRef = longArrayOf(RssReader.rssMb() ?: 0L)
            val text = StringBuilder()
            val done = CompletableDeferred<Unit>()
            try {
                val backend = mapBackend(request.config.backend)
                // Rebuild conversation so the full GenerateRequest history is authoritative.
                val history = splitHistory(request.messages, request.config)
                val conversation = try {
                    session.conversation?.let { runCatching { it.close() } }
                    val conv = createConversation(session.engine, request.config, history.seed)
                    session.conversation = conv
                    conv
                } catch (t: Throwable) {
                    throw EngineException.GenerateFailed(
                        "LiteRT conversation rebuild failed: ${t.message}", t,
                    )
                }

                collector.onGenerateStart(promptTokens = 0)
                val lastIndex = intArrayOf(0)

                conversation.sendMessageAsync(
                    Contents.of(Content.Text(history.lastUser)),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val piece = message.toString()
                            if (piece.isNotEmpty()) {
                                collector.onToken()
                                text.append(piece)
                                lastIndex[0] += 1
                                onEvent(EngineEvent.Token(piece, lastIndex[0]))
                            }
                        }

                        override fun onDone() {
                            done.complete(Unit)
                        }

                        override fun onError(throwable: Throwable) {
                            done.completeExceptionally(throwable)
                        }
                    },
                    emptyMap<String, Any>(),
                )

                try {
                    done.await()
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    if (cause is kotlinx.coroutines.CancellationException ||
                        cause is java.util.concurrent.CancellationException
                    ) {
                        throw EngineException.Cancelled()
                    }
                    throw EngineException.GenerateFailed(
                        cause.message ?: "LiteRT generate failed", cause,
                    )
                }

                // One sample per turn: a per-token /proc/self/status read would
                // sit on the streaming hot path and inflate latency.
                RssReader.rssMb()?.let { if (it > rssPeakRef[0]) rssPeakRef[0] = it }
                val generated = lastIndex[0].coerceAtLeast(0)
                val metrics = collector.build(
                    generatedTokens = generated,
                    loadMs = session.loadMs,
                    rssMbLoad = session.rssMbLoad,
                    rssMbPeak = rssPeakRef[0],
                    effectiveConfig = request.config,
                    warnings = warningsFor(request.config) +
                        (if (backend !== mapBackend(session.config.backend)) {
                            listOf("backend switch requires reload; used load-time backend")
                        } else {
                            emptyList()
                        }),
                )
                session.metrics = metrics
                onEvent(
                    EngineEvent.Done(
                        GenerateResult(
                            text = text.toString(),
                            promptTokens = metrics.promptTokens,
                            generatedTokens = generated,
                            metrics = metrics,
                        ),
                    ),
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                runCatching { session.conversation?.cancelProcess() }
                onEvent(EngineEvent.Error(EngineException.Cancelled()))
            } catch (e: EngineException) {
                onEvent(EngineEvent.Error(e))
            } catch (t: Throwable) {
                onEvent(
                    EngineEvent.Error(
                        EngineException.GenerateFailed(t.message ?: "LiteRT generate failed", t),
                    ),
                )
            } finally {
                session.generating.set(false)
                scope.cancel()
            }
        }
        return LiteRtJob(job)
    }

    override suspend fun reset(handle: SessionHandle) {
        val session = handle as? LiteRtSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("cannot reset while generating")
        }
        try {
            session.conversation?.close()
        } catch (_: Throwable) {
            // ignore close errors on reset
        }
        session.conversation = createConversation(session.engine, session.config, emptyList())
    }

    override suspend fun unload(handle: SessionHandle) {
        val session = handle as? LiteRtSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("unload called while generating; cancel first")
        }
        if (session.closed.compareAndSet(false, true)) {
            runCatching { session.conversation?.close() }
            runCatching { session.engine.close() }
            session.conversation = null
            session.metrics = null
        }
    }

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? =
        (handle as? LiteRtSession)?.metrics

    private data class SplitHistory(
        val seed: List<Message>,
        val lastUser: String,
    )

    private fun splitHistory(
        messages: List<ChatMessage>,
        config: InferenceConfig,
    ): SplitHistory {
        val effective = messages.ifEmpty {
            listOfNotNull(config.systemPrompt?.let { ChatMessage(ChatRole.SYSTEM, it) })
        }
        val lastUserIndex = effective.indexOfLast { it.role == ChatRole.USER }
        if (lastUserIndex < 0) {
            throw EngineException.GenerateFailed("no USER message in request")
        }
        val seed = ArrayList<Message>()
        for (i in 0 until lastUserIndex) {
            val m = effective[i]
            when (m.role) {
                ChatRole.USER -> seed += Message.user(m.content)
                ChatRole.ASSISTANT -> seed += Message.model(m.content)
                ChatRole.SYSTEM -> Unit // handled via systemInstruction
            }
        }
        return SplitHistory(seed = seed, lastUser = effective[lastUserIndex].content)
    }

    private fun createConversation(
        engine: Engine,
        config: InferenceConfig,
        initialMessages: List<Message>,
    ): Conversation {
        val system = config.systemPrompt
        val sampler = mapSampler(config)
        return engine.createConversation(
            ConversationConfig(
                samplerConfig = sampler,
                systemInstruction = system?.let { Contents.of(it) },
                initialMessages = initialMessages,
            ),
        )
    }

    private fun mapSampler(config: InferenceConfig): SamplerConfig? {
        // NPU path in gallery passes null SamplerConfig; keep that behavior.
        return if (config.backend == AppBackend.NPU_HTP) {
            null
        } else {
            SamplerConfig(
                topK = config.topK,
                topP = config.topP.toDouble(),
                temperature = config.temperature.toDouble(),
            )
        }
    }

    private fun mapBackend(backend: AppBackend): Backend = when (backend) {
        AppBackend.CPU -> Backend.CPU()
        AppBackend.GPU -> Backend.GPU()
        AppBackend.NPU_HTP -> Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
        AppBackend.AUTO -> Backend.GPU()
        AppBackend.OPENCL ->
            throw EngineException.UnsupportedBackend(
                "LiteRT-LM uses GPU delegate, not OPENCL; got ${backend}",
            )
    }

    private fun resolveModelPath(model: LocalModel): String {
        return when (val loc = model.location) {
            is ModelLocation.FilePath -> loc.path
            is ModelLocation.AppPrivate -> loc.relativePath
            is ModelLocation.SafUri ->
                throw EngineException.LoadFailed(
                    "SAF uri not supported for LiteRT yet; use absolute path",
                )
        }
    }

    private fun warningsFor(config: InferenceConfig, rssBefore: Long? = null): List<String> {
        val w = mutableListOf<String>()
        when (config.backend) {
            AppBackend.AUTO -> w += "Backend.AUTO resolved to GPU"
            AppBackend.NPU_HTP -> w += "SamplerConfig omitted on NPU (engine default)"
            else -> Unit
        }
        if (config.threads != 4) {
            w += "threads=${config.threads} not applied (LiteRT manages its own thread pool)"
        }
        if (config.seed != null) {
            w += "seed ignored by LiteRT-LM"
        }
        rssBefore?.let { w += "rss baseline before load: ${it}MB" }
        return w
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LiteRtEngineModule {
    @Binds
    @IntoSet
    abstract fun bindEngine(impl: LiteRtEngine): LlmEngine
}

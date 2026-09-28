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

        /**
         * Chat turns already applied to [conversation] (system excluded).
         * `null` means the conversation is dirty (cancelled mid-turn) and the
         * next [generate] must rebuild from [GenerateRequest.messages].
         */
        @Volatile
        var liveHistory: List<ChatMessage>? = emptyList()

        @Volatile
        var metrics: EngineMetrics? = null

        override val isClosed: Boolean get() = closed.get()
    }

    private class LiteRtJob(
        private val job: Job,
        private val cancelled: AtomicBoolean,
        private val onNativeCancel: () -> Unit,
    ) : GenerateJob {
        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                onNativeCancel()
            }
            job.cancel()
        }
        override val isActive: Boolean get() = !cancelled.get() && job.isActive
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
        val cancelled = AtomicBoolean(false)
        val job = scope.launch {
            val collector = MetricsCollector()
            val rssPeakRef = longArrayOf(RssReader.rssMb() ?: 0L)
            val text = StringBuilder()
            val done = CompletableDeferred<Unit>()
            try {
                val backend = mapBackend(request.config.backend)
                val history = splitHistory(request.messages, request.config)

                // Gallery keeps one Conversation and only sends the new USER turn;
                // history accumulates natively. Rebuild (initialMessages seed) only when
                // GenerateRequest.history diverges from what the live conversation has.
                val conversation = try {
                    val live = session.liveHistory
                    val sameHistory = live != null && live == history.seed
                    val conv = if (sameHistory && session.conversation != null) {
                        session.conversation!!
                    } else {
                        session.conversation?.let { runCatching { it.close() } }
                        createConversation(session.engine, request.config, history.seed).also {
                            session.conversation = it
                            session.liveHistory = history.seed
                        }
                    }
                    conv
                } catch (t: Throwable) {
                    throw EngineException.GenerateFailed(
                        "LiteRT conversation rebuild failed: ${t.message}", t,
                    )
                }

                collector.onGenerateStart(promptTokens = 0)
                val lastIndex = intArrayOf(0)
                val maxNew = request.config.maxNewTokens.coerceAtLeast(1)
                val hitTokenCap = AtomicBoolean(false)
                val hitRepeat = AtomicBoolean(false)

                // Match gallery: thinking is a runtime flag, not a text suffix.
                val extraContext = mapOf<String, Any>(
                    "enable_thinking" to request.config.enableThinking,
                )

                conversation.sendMessageAsync(
                    Contents.of(Content.Text(history.lastUser)),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (cancelled.get() || hitTokenCap.get() || hitRepeat.get()) return
                            val piece = message.toString()
                            if (piece.isEmpty()) return

                            // LiteRT may deliver either a token delta or the accumulated
                            // text so far. Accept both so a cumulative callback cannot
                            // paint endless "您好您好…".
                            val current = text.toString()
                            val delta = when {
                                current.isEmpty() -> piece
                                piece == current -> return
                                piece.startsWith(current) -> piece.substring(current.length)
                                else -> piece
                            }
                            if (delta.isEmpty()) return

                            collector.onToken()
                            text.append(delta)
                            lastIndex[0] += 1
                            onEvent(EngineEvent.Token(delta, lastIndex[0]))

                            // Small models can miss EOS and loop a greeting
                            // ("您好您好…"). Cut the turn before it fills maxNewTokens.
                            if (isDegenerateRepeat(text.toString())) {
                                hitRepeat.set(true)
                                runCatching { conversation.cancelProcess() }
                                return
                            }
                            if (lastIndex[0] >= maxNew) {
                                hitTokenCap.set(true)
                                runCatching { conversation.cancelProcess() }
                            }
                        }

                        override fun onDone() {
                            done.complete(Unit)
                        }

                        override fun onError(throwable: Throwable) {
                            if (hitTokenCap.get() || hitRepeat.get()) {
                                done.complete(Unit)
                            } else {
                                done.completeExceptionally(throwable)
                            }
                        }
                    },
                    extraContext,
                )

                try {
                    done.await()
                } catch (t: Throwable) {
                    if (hitTokenCap.get() || hitRepeat.get()) {
                        // cancelled on purpose (max tokens / degenerate repeat)
                    } else {
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
                        }) +
                        (if (hitRepeat.get()) {
                            listOf("输出陷入短语重复循环，已提前截断")
                        } else {
                            emptyList()
                        }),
                )
                session.metrics = metrics
                session.liveHistory = history.seed + listOf(
                    ChatMessage(ChatRole.USER, history.lastUser),
                    ChatMessage(ChatRole.ASSISTANT, text.toString()),
                )
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
                session.liveHistory = null
                onEvent(EngineEvent.Error(EngineException.Cancelled()))
            } catch (e: EngineException) {
                if (cancelled.get()) {
                    session.liveHistory = null
                    onEvent(EngineEvent.Error(EngineException.Cancelled()))
                } else {
                    onEvent(EngineEvent.Error(e))
                }
            } catch (t: Throwable) {
                if (cancelled.get()) {
                    session.liveHistory = null
                    onEvent(EngineEvent.Error(EngineException.Cancelled()))
                } else {
                    onEvent(
                        EngineEvent.Error(
                            EngineException.GenerateFailed(t.message ?: "LiteRT generate failed", t),
                        ),
                    )
                }
            } finally {
                session.generating.set(false)
                scope.cancel()
            }
        }
        return LiteRtJob(job, cancelled) {
            runCatching { session.conversation?.cancelProcess() }
        }
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
        session.liveHistory = emptyList()
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
        val seed: List<ChatMessage>,
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
        val seed = ArrayList<ChatMessage>()
        for (i in 0 until lastUserIndex) {
            val m = effective[i]
            when (m.role) {
                ChatRole.USER, ChatRole.ASSISTANT -> seed += m
                ChatRole.SYSTEM -> Unit // handled via systemInstruction
            }
        }
        return SplitHistory(seed = seed, lastUser = effective[lastUserIndex].content)
    }

    private fun List<ChatMessage>.toLitertMessages(): List<Message> = map { m ->
        when (m.role) {
            ChatRole.USER -> Message.user(m.content)
            ChatRole.ASSISTANT -> Message.model(m.content)
            ChatRole.SYSTEM -> Message.user(m.content) // unused: system goes via systemInstruction
        }
    }

    /**
     * True when [s] ends in a short unit repeated many times ("您好您好…").
     * Small LiteRT models sometimes miss EOS after a greeting and loop.
     */
    internal fun isDegenerateRepeat(s: String): Boolean {
        if (s.length < 10) return false
        val window = s.takeLast(16)
        for (period in 1..4) {
            if (window.length % period != 0) continue
            val unit = window.substring(0, period)
            if (unit.all { it.isWhitespace() }) continue
            val repeats = window.length / period
            if (repeats >= 5 && window == unit.repeat(repeats)) return true
        }
        return false
    }

    private fun createConversation(
        engine: Engine,
        config: InferenceConfig,
        initialMessages: List<ChatMessage>,
    ): Conversation {
        val system = config.systemPrompt
        val sampler = mapSampler(config)
        return engine.createConversation(
            ConversationConfig(
                samplerConfig = sampler,
                systemInstruction = system?.let { Contents.of(it) },
                initialMessages = initialMessages.toLitertMessages(),
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

package io.github.pisces312.droidllm.engine.genie

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.pisces312.droidllm.chattemplate.ChatTemplate
import io.github.pisces312.droidllm.common.metrics.MetricsCollector
import io.github.pisces312.droidllm.common.metrics.RssReader
import io.github.pisces312.droidllm.engine.genie.BuildConfig
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.EngineDefaults
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.EngineLogging
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
import kotlinx.coroutines.launch

/**
 * Qualcomm Genie (QAIRT/QNN HTP) adapter.
 *
 * - HTP only: NPU_HTP / AUTO accepted; CPU / GPU / OPENCL rejected.
 * - Model dir: genie_config.json + tokenizer.json + *.bin (AI Hub / chatapp layout).
 * - Multi-turn: GenerateRequest.messages is authoritative. Incremental query when
 *   history matches what the dialog already holds; otherwise reset + full replay.
 * - Empty reply: GenieDialog_reset + retry (≤2 attempts), then GenerateFailed.
 */
@Singleton
class GenieEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val configResolver: GenieConfigResolver,
) : LlmEngine {

    override val id: EngineId = EngineId.GENIE
    override val displayName: String get() = id.displayName
    override val version: EngineVersion = EngineVersion(
        version = BuildConfig.ENGINE_VERSION.takeIf { it.isNotBlank() },
        commit = BuildConfig.ENGINE_COMMIT.takeIf { it.isNotBlank() },
    )

    /**
     * App baseline, now that there is no app-wide settings layer.
     *
     * `backend = NPU_HTP` is the point of this engine and the only backend it
     * accepts (`load` rejects everything else), so it is stated explicitly. That
     * also matters beyond cosmetics: an app-wide `CPU` default would have made
     * Genie throw `UnsupportedBackend` on every session.
     *
     * `threads` is ignored (HTP schedules on its own) — see `warningsFor`.
     */
    override val defaults: EngineDefaults = EngineDefaults(
        temperature = 0.7f,
        topK = 40,
        topP = 0.95f,
        threads = 4,
        maxNewTokens = 4096,
        backend = Backend.NPU_HTP,
    )

    private class GenieSession(
        override val modelId: String,
        val handle: Long,
        val config: InferenceConfig,
        val loadMs: Long,
        val rssMbLoad: Long?,
        val modelPath: String,
        val promptTags: PromptTags,
    ) : SessionHandle {
        override val engineId: EngineId = EngineId.GENIE
        val closed = AtomicBoolean(false)
        val generating = AtomicBoolean(false)

        /** Messages already reflected in the Genie dialog KV cache. */
        @Volatile
        var fedMessages: List<ChatMessage> = emptyList()

        @Volatile
        var metrics: EngineMetrics? = null

        override val isClosed: Boolean get() = closed.get()
    }

    private class GenieJob(
        private val job: Job,
        private val cancelled: AtomicBoolean,
    ) : GenerateJob {
        override fun cancel() {
            cancelled.set(true)
            job.cancel()
        }
        override val isActive: Boolean get() = !cancelled.get() && job.isActive
    }

    /** Role tags from model metadata.json (AI Hub) or generic fallback. */
    data class PromptTags(
        val systemPrefix: String,
        val systemSuffix: String,
        val userPrefix: String,
        val userSuffix: String,
        val assistantPrefix: String,
    )

    @Volatile
    private var nativeReady: Boolean? = null

    /**
     * Also installs the QNN/adsprpc library search path — must happen before
     * `libgenie_chat_jni.so` (and with it libGenie / libQnnHtp) is loaded, hence
     * ahead of the first [GenieNative] touch. See [QnnEnv].
     */
    private fun ensureNative(): Boolean {
        nativeReady?.let { return it }
        return try {
            QnnEnv.ensure(context.applicationInfo.nativeLibraryDir)
            GenieNative.nativeVersion()
            nativeReady = true
            true
        } catch (_: Throwable) {
            nativeReady = false
            false
        }
    }

    override suspend fun probe(probeContext: ProbeContext): Availability {
        if (!ensureNative()) {
            return Availability.MissingDependency(
                context.getString(R.string.genie_native_missing),
            )
        }
        val soc = probeContext.socModel ?: "unknown"
        if (soc !in GenieConfigResolver.SOC_TO_HTP) {
            return Availability.UnsupportedSoc(
                context.getString(
                    R.string.genie_unsupported_soc,
                    soc,
                    configResolver.supportedSocs().joinToString(),
                ),
            )
        }
        if (configResolver.resolveHtpConfigPath() == null) {
            return Availability.MissingDependency(context.getString(R.string.genie_htp_config_missing))
        }
        return Availability.Available
    }

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle {
        if (!ensureNative()) {
            throw EngineException.LoadFailed("Genie native library unavailable")
        }
        // Reject non-HTP backends up front (contract: no silent fallback).
        when (config.backend) {
            Backend.NPU_HTP, Backend.AUTO -> Unit
            else -> throw EngineException.UnsupportedBackend(
                context.getString(R.string.genie_backend_unsupported, config.backend),
            )
        }

        val path = resolveModelPath(model)
        val dir = File(path)
        configResolver.validateModelDir(dir)?.let { reason ->
            throw EngineException.LoadFailed(context.getString(R.string.genie_model_invalid, reason))
        }
        val htpConfig = configResolver.resolveHtpConfigPath()
            ?: throw EngineException.LoadFailed(
                context.getString(R.string.genie_no_htp_config, configResolver.supportedSocs()),
            )

        val overrides = GenieConfigResolver.ConfigOverrides(
            temperature = config.temperature.toDouble(),
            topK = config.topK,
            topP = config.topP.toDouble(),
            seed = config.seed,
            maxNewTokens = config.maxNewTokens,
        )
        val configJson = try {
            configResolver.buildResolvedConfig(dir, htpConfig, overrides)
        } catch (t: Throwable) {
            throw EngineException.LoadFailed(
                context.getString(R.string.genie_config_parse_failed, t.message),
                t,
            )
        }

        val start = System.nanoTime()
        val handle = GenieNative.nativeCreate(configJson)
        if (handle == 0L) {
            throw EngineException.LoadFailed("GenieDialog_create failed for $path")
        }
        GenieNative.nativeSetMaxNumTokens(handle, config.maxNewTokens)
        val loadMs = (System.nanoTime() - start) / 1_000_000
        val rss = RssReader.rssMb()

        val session = GenieSession(
            modelId = model.id,
            handle = handle,
            config = config,
            loadMs = loadMs,
            rssMbLoad = rss,
            modelPath = path,
            promptTags = loadPromptTags(dir),
        )
        session.metrics = EngineMetrics(
            loadMs = loadMs,
            rssMbLoad = rss,
            rssMbPeak = rss,
            effectiveConfig = config,
            warnings = warningsFor(config),
        )
        return session
    }

    override fun generate(
        handle: SessionHandle,
        request: GenerateRequest,
        onEvent: (EngineEvent) -> Unit,
    ): GenerateJob {
        val session = handle as? GenieSession
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
            try {
                GenieNative.nativeSetMaxNumTokens(session.handle, request.config.maxNewTokens)
                val prompt = buildPromptFor(session, request.messages)
                collector.onGenerateStart(promptTokens = 0)

                val metricsOut = LongArray(2)
                var produced = false
                var attempts = 0
                val maxAttempts = 3 // initial + ≤2 retries after empty reply (DESIGN §6)

                while (attempts < maxAttempts && !produced) {
                    attempts++
                    if (attempts > 1) {
                        // Known Genie quirk: empty reply mid-chat. Reset + retry.
                        GenieNative.nativeReset(session.handle)
                        text.setLength(0)
                    }
                    metricsOut[0] = 0L
                    metricsOut[1] = 0L
                    val ok = GenieNative.nativeGenerate(
                        session.handle,
                        prompt,
                        GenieNative.TokenCallback { piece ->
                            if (cancelled.get()) return@TokenCallback
                            if (piece.isNotEmpty()) {
                                collector.onToken()
                                text.append(piece)
                                onEvent(EngineEvent.Token(piece, text.length))
                            }
                        },
                        metricsOut,
                    )
                    // One sample per attempt: a per-token /proc/self/status read
                    // would sit on the streaming hot path and inflate latency.
                    RssReader.rssMb()?.let { if (it > rssPeakRef[0]) rssPeakRef[0] = it }
                    produced = ok && text.isNotEmpty()
                }

                if (cancelled.get()) {
                    onEvent(EngineEvent.Error(EngineException.Cancelled()))
                    return@launch
                }

                if (!produced) {
                    val status = metricsOut[1].toInt()
                    val reason = when (status) {
                        1 -> context.getString(R.string.genie_empty_reply, attempts)
                        2 -> context.getString(R.string.genie_query_failed)
                        else -> context.getString(R.string.genie_no_token)
                    }
                    throw EngineException.GenerateFailed(reason)
                }

                val generatedPieces = metricsOut[0].toInt().coerceAtLeast(text.length)
                // Genie streams text pieces, not discrete tokens — approximate.
                val generatedTokens = generatedPieces
                val metrics = collector.build(
                    generatedTokens = generatedTokens,
                    loadMs = session.loadMs,
                    rssMbLoad = session.rssMbLoad,
                    rssMbPeak = rssPeakRef[0],
                    effectiveConfig = request.config,
                    warnings = warningsFor(request.config) +
                        context.getString(R.string.genie_stream_note) +
                        if (attempts > 1) {
                            context.getString(R.string.genie_retry_note, attempts - 1)
                        } else {
                            ""
                        },
                )
                session.metrics = metrics
                session.fedMessages = request.messages
                onEvent(
                    EngineEvent.Done(
                        GenerateResult(
                            text = text.toString(),
                            promptTokens = 0,
                            generatedTokens = generatedTokens,
                            metrics = metrics,
                        ),
                    ),
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                onEvent(EngineEvent.Error(EngineException.Cancelled()))
            } catch (t: Throwable) {
                if (cancelled.get()) {
                    onEvent(EngineEvent.Error(EngineException.Cancelled()))
                } else {
                    onEvent(
                        EngineEvent.Error(
                            if (t is EngineException) t
                            else EngineException.GenerateFailed(t.message ?: "Genie generate failed", t),
                        ),
                    )
                }
            } finally {
                session.generating.set(false)
                scope.cancel()
            }
        }
        return GenieJob(job, cancelled)
    }

    override suspend fun reset(handle: SessionHandle) {
        val session = handle as? GenieSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("cannot reset while generating")
        }
        GenieNative.nativeReset(session.handle)
        session.fedMessages = emptyList()
    }

    override suspend fun unload(handle: SessionHandle) {
        val session = handle as? GenieSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("unload called while generating; cancel first")
        }
        if (session.closed.compareAndSet(false, true)) {
            GenieNative.nativeDestroy(session.handle)
            session.metrics = null
            session.fedMessages = emptyList()
        }
    }

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? =
        (handle as? GenieSession)?.metrics

    // --- prompt / history -------------------------------------------------

    /**
     * Incremental when request.messages extends session.fedMessages with one new
     * user turn; otherwise reset the dialog and send a single full-history prompt.
     */
    private fun buildPromptFor(session: GenieSession, messages: List<ChatMessage>): String {
        val history = if (messages.isNotEmpty()) messages.dropLast(1) else emptyList()
        val last = messages.lastOrNull()
            ?: throw EngineException.GenerateFailed("empty message list")
        if (last.role != ChatRole.USER) {
            throw EngineException.GenerateFailed("last message must be USER")
        }

        val incremental = history == session.fedMessages
        if (!incremental) {
            GenieNative.nativeReset(session.handle)
            session.fedMessages = emptyList()
            return formatFullPrompt(session.promptTags, messages)
        }
        // Dialog already holds history; send only the new user turn with tags.
        return formatUserTurn(session.promptTags, last, isFirstTurn = session.fedMessages.isEmpty())
    }

    private fun formatFullPrompt(tags: PromptTags, messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        var firstUser = true
        for (m in messages) {
            when (m.role) {
                ChatRole.SYSTEM -> {
                    sb.append(tags.systemPrefix).append(m.content).append(tags.systemSuffix)
                }
                ChatRole.USER -> {
                    if (firstUser && !messages.any { it.role == ChatRole.SYSTEM }) {
                        // no-op: system prefix is only emitted when a system msg exists
                    }
                    firstUser = false
                    sb.append(tags.userPrefix).append(m.content).append(tags.userSuffix)
                    sb.append(tags.assistantPrefix)
                }
                ChatRole.ASSISTANT -> {
                    // Drop the pending assistant prefix; write the reply as a completed turn.
                    if (sb.endsWith(tags.assistantPrefix)) {
                        sb.setLength(sb.length - tags.assistantPrefix.length)
                    }
                    sb.append(m.content)
                }
            }
        }
        if (!sb.endsWith(tags.assistantPrefix)) {
            sb.append(tags.assistantPrefix)
        }
        return sb.toString()
    }

    private fun formatUserTurn(tags: PromptTags, user: ChatMessage, isFirstTurn: Boolean): String {
        val sb = StringBuilder()
        if (isFirstTurn) {
            // System prompt (if any) is applied at load via config; only user tags here.
        }
        sb.append(tags.userPrefix).append(user.content).append(tags.userSuffix)
        sb.append(tags.assistantPrefix)
        return sb.toString()
    }

    private fun loadPromptTags(modelDir: File): PromptTags {
        // AI Hub models ship metadata.json with genie.chat_template (chatapp layout).
        val meta = File(modelDir, "metadata.json")
        if (meta.isFile) {
            try {
                val text = meta.readText()
                fun pick(key: String, default: String): String {
                    val needle = "\"$key\""
                    val idx = text.indexOf(needle)
                    if (idx < 0) return default
                    val colon = text.indexOf(':', idx)
                    if (colon < 0) return default
                    val q1 = text.indexOf('"', colon + 1)
                    if (q1 < 0) return default
                    val q2 = text.indexOf('"', q1 + 1)
                    if (q2 < 0) return default
                    return text.substring(q1 + 1, q2)
                }
                return PromptTags(
                    systemPrefix = pick("system_prefix", ""),
                    systemSuffix = pick("system_suffix", "\n"),
                    userPrefix = pick("user_prefix", "user: "),
                    userSuffix = pick("user_suffix", "\n"),
                    assistantPrefix = pick("assistant_prefix", "assistant: "),
                )
            } catch (_: Throwable) {
                // fall through
            }
        }
        return PromptTags(
            systemPrefix = "",
            systemSuffix = "\n",
            userPrefix = "user: ",
            userSuffix = "\n",
            assistantPrefix = "assistant: ",
        )
    }

    private fun resolveModelPath(model: LocalModel): String {
        return when (val loc = model.location) {
            is ModelLocation.FilePath -> loc.path
            is ModelLocation.AppPrivate -> loc.relativePath
            is ModelLocation.SafUri ->
                throw EngineException.LoadFailed("SAF uri not supported for Genie yet; use absolute path")
        }
    }

    private fun warningsFor(config: InferenceConfig): List<String> {
        val w = mutableListOf<String>()
        when (config.backend) {
            Backend.AUTO -> w += "Backend.AUTO resolved to HTP"
            else -> Unit
        }
        w += "threads ignored for GENIE"
        if (config.seed == null) w += "seed not set (Genie sampler uses genie_config.json)"
        return w
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GenieEngineModule {
    companion object {
        /**
         * Binds through [EngineLogging.wrap] so every call is recorded by the
         * app's diagnostic sink. The sink is installed in `Application.onCreate`,
         * after Hilt has built the graph, so the decorator resolves it lazily
         * per call and must not capture it at construction time.
         */
        @Provides
        @IntoSet
        fun provideLoggedEngine(impl: GenieEngine): LlmEngine =
            EngineLogging.wrap(impl)
    }
}

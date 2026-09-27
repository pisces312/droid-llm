package io.github.pisces312.droidllm.engine.mnn

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.pisces312.droidllm.common.metrics.MetricsCollector
import io.github.pisces312.droidllm.common.metrics.RssReader
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engine.mnn.BuildConfig
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
import kotlinx.coroutines.launch

/**
 * MNN LLM adapter. Links prebuilt libMNN.so (MNN_BUILD_LLM=ON).
 * Backend: CPU / OPENCL (GPU maps to OPENCL). NPU_HTP rejected.
 * MNN applies its own chat template via Llm::response(ChatMessages).
 */
@Singleton
class MnnEngine @Inject constructor() : LlmEngine {

    override val id: EngineId = EngineId.MNN
    override val displayName: String = "MNN"
    override val version: EngineVersion = EngineVersion(
        version = BuildConfig.ENGINE_VERSION.takeIf { it.isNotBlank() },
        commit = BuildConfig.ENGINE_COMMIT.takeIf { it.isNotBlank() },
    )

    private class MnnSession(
        override val modelId: String,
        val handle: Long,
        val config: InferenceConfig,
        val loadMs: Long,
        val rssMbLoad: Long?,
        val modelPath: String,
    ) : SessionHandle {
        override val engineId: EngineId = EngineId.MNN
        val closed = AtomicBoolean(false)
        val generating = AtomicBoolean(false)

        @Volatile
        var metrics: EngineMetrics? = null

        override val isClosed: Boolean get() = closed.get()
    }

    private class MnnJob(private val job: Job) : GenerateJob {
        override fun cancel() = job.cancel()
        override val isActive: Boolean get() = job.isActive
    }

    @Volatile
    private var nativeReady: Boolean? = null

    private fun ensureNative(): Boolean {
        nativeReady?.let { return it }
        return try {
            MnnNative.nativeVersion()
            nativeReady = true
            true
        } catch (_: Throwable) {
            nativeReady = false
            false
        }
    }

    override suspend fun probe(probeContext: ProbeContext): Availability {
        if (!ensureNative()) {
            return Availability.MissingDependency("libmnn_chat_jni.so / libMNN.so failed to load")
        }
        return Availability.Available
    }

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle {
        if (!ensureNative()) {
            throw EngineException.LoadFailed("MNN native library unavailable")
        }
        val path = resolveModelPath(model)
        val dir = File(path)
        if (!dir.isDirectory) {
            throw EngineException.LoadFailed("MNN model directory not found: $path")
        }
        val configFile = File(dir, "config.json")
        if (!configFile.isFile) {
            throw EngineException.LoadFailed("missing config.json in $path")
        }

        val start = System.nanoTime()
        val rssBefore = RssReader.rssMb()
        // Llm::createLLM takes either `<dir>/` or `<dir>/config.json` and derives
        // every other path by string concatenation onto base_dir. A bare directory
        // yields `<dir>tokenizer.txt`, so always hand it the config file.
        val handle = MnnNative.nativeCreate(configFile.absolutePath)
        if (handle == 0L) {
            throw EngineException.LoadFailed("Llm::createLLM/load failed for $path")
        }
        applyConfig(handle, config)
        val loadMs = (System.nanoTime() - start) / 1_000_000
        val rssAfter = RssReader.rssMb()
        val session = MnnSession(
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
        val session = handle as? MnnSession
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
            try {
                applyConfig(session.handle, request.config)
                val flat = flattenMessages(request.messages)
                collector.onGenerateStart(promptTokens = 0)
                val metricsOut = LongArray(5)

                MnnNative.nativeGenerate(
                    session.handle,
                    flat,
                    request.config.maxNewTokens,
                    MnnNative.TokenCallback { piece ->
                        if (piece.isNotEmpty()) {
                            collector.onToken()
                            text.append(piece)
                            onEvent(EngineEvent.Token(piece, text.length))
                            RssReader.rssMb()?.let { if (it > rssPeakRef[0]) rssPeakRef[0] = it }
                        }
                    },
                    metricsOut,
                )

                val promptTokens = metricsOut[0].toInt().coerceAtLeast(0)
                val generatedNative = metricsOut[1].toInt().coerceAtLeast(0)
                collector.onPromptTokenCount(promptTokens)
                val generated = if (generatedNative > 0) generatedNative else text.length
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
                        else EngineException.GenerateFailed(t.message ?: "MNN generate failed", t),
                    ),
                )
            } finally {
                session.generating.set(false)
                scope.cancel()
            }
        }
        return MnnJob(job)
    }

    override suspend fun reset(handle: SessionHandle) {
        val session = handle as? MnnSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("cannot reset while generating")
        }
        MnnNative.nativeReset(session.handle)
    }

    override suspend fun unload(handle: SessionHandle) {
        val session = handle as? MnnSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("unload called while generating; cancel first")
        }
        if (session.closed.compareAndSet(false, true)) {
            MnnNative.nativeDestroy(session.handle)
            session.metrics = null
        }
    }

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? =
        (handle as? MnnSession)?.metrics

    private fun resolveModelPath(model: LocalModel): String {
        return when (val loc = model.location) {
            is ModelLocation.FilePath -> loc.path
            is ModelLocation.AppPrivate -> loc.relativePath
            is ModelLocation.SafUri ->
                throw EngineException.LoadFailed("SAF uri not supported for MNN yet; use absolute path")
        }
    }

    private fun applyConfig(handle: Long, config: InferenceConfig) {
        val backend = when (config.backend) {
            Backend.CPU, Backend.AUTO -> "cpu"
            Backend.OPENCL, Backend.GPU -> "opencl"
            Backend.NPU_HTP ->
                throw EngineException.UnsupportedBackend("MNN does not use NPU_HTP; got ${config.backend}")
        }
        val json = buildString {
            append('{')
            append("\"backend_type\":\"").append(backend).append("\",")
            append("\"thread_num\":").append(config.threads).append(',')
            append("\"max_new_tokens\":").append(config.maxNewTokens).append(',')
            append("\"temperature\":").append(config.temperature).append(',')
            append("\"top_k\":").append(config.topK).append(',')
            append("\"top_p\":").append(config.topP)
            append('}')
        }
        MnnNative.nativeSetConfig(handle, json)
    }

    private fun warningsFor(config: InferenceConfig, rssBefore: Long? = null): List<String> {
        val w = mutableListOf<String>()
        when (config.backend) {
            Backend.AUTO -> w += "Backend.AUTO resolved to cpu"
            Backend.GPU -> w += "Backend.GPU mapped to opencl"
            else -> Unit
        }
        rssBefore?.let { w += "rss baseline before load: ${it}MB" }
        return w
    }

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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MnnEngineModule {
    @Binds
    @IntoSet
    abstract fun bindEngine(impl: MnnEngine): LlmEngine
}

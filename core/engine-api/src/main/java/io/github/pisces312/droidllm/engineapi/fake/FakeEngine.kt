package io.github.pisces312.droidllm.engineapi.fake

import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.EngineEvent
import io.github.pisces312.droidllm.engineapi.EngineException
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.EngineMetrics
import io.github.pisces312.droidllm.engineapi.GenerateJob
import io.github.pisces312.droidllm.engineapi.GenerateRequest
import io.github.pisces312.droidllm.engineapi.GenerateResult
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ProbeContext
import io.github.pisces312.droidllm.engineapi.SessionHandle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reference implementation of [LlmEngine] used for UI wiring and as a living
 * specification of the interface contract. Emits lorem-ipsum tokens with a
 * fixed delay and fabricates plausible TTFT / decode metrics.
 */
class FakeEngine : LlmEngine {

    override val id: EngineId = EngineId.FAKE
    override val displayName: String = "FakeEngine"

    private class FakeSession(
        override val modelId: String,
        val config: InferenceConfig,
    ) : SessionHandle {
        override val engineId: EngineId = EngineId.FAKE
        val closed = AtomicBoolean(false)
        val generating = AtomicBoolean(false)
        @Volatile
        var metrics: EngineMetrics? = null
        override val isClosed: Boolean get() = closed.get()
    }

    private class FakeJob(
        private val job: Job,
    ) : GenerateJob {
        override fun cancel() = job.cancel()
        override val isActive: Boolean get() = job.isActive
    }

    override suspend fun probe(probeContext: ProbeContext): Availability = Availability.Available

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle {
        val start = System.nanoTime()
        delay(120)
        return FakeSession(model.id, config).also {
            it.metrics = EngineMetrics(
                loadMs = (System.nanoTime() - start) / 1_000_000,
                effectiveConfig = config,
            )
        }
    }

    override fun generate(
        handle: SessionHandle,
        request: GenerateRequest,
        onEvent: (EngineEvent) -> Unit,
    ): GenerateJob {
        val session = handle as? FakeSession
            ?: throw EngineException.InvalidState("unknown session handle")
        if (session.isClosed) {
            throw EngineException.InvalidState("session already closed")
        }
        if (!session.generating.compareAndSet(false, true)) {
            throw EngineException.InvalidState("generate already running on this session")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            try {
                val maxTokens = request.config.maxNewTokens.coerceIn(1, 256)
                val words = FAKE_WORDS
                val start = System.nanoTime()
                var firstTokenMs: Long? = null
                val tokenTimes = mutableListOf<Long>()

                delay(80) // fake prefill
                var emitted = 0
                while (emitted < maxTokens) {
                    val chunk = words[emitted % words.size]
                    val now = System.nanoTime()
                    if (firstTokenMs == null) {
                        firstTokenMs = (now - start) / 1_000_000
                    }
                    tokenTimes += now
                    onEvent(EngineEvent.Token(chunk, emitted))
                    emitted++
                    delay(28)
                }

                val totalMs = (System.nanoTime() - start) / 1_000_000
                val ttft = firstTokenMs ?: totalMs
                val decodeSeconds = ((totalMs - ttft).coerceAtLeast(1)).toDouble() / 1000.0
                val generated = emitted
                val decodeTps = if (generated > 1) (generated - 1) / decodeSeconds else null
                val metrics = EngineMetrics(
                    loadMs = session.metrics?.loadMs,
                    ttftMs = ttft,
                    prefillTps = 256.0 / (ttft.coerceAtLeast(1).toDouble() / 1000.0),
                    decodeTps = decodeTps,
                    perTokenMsP50 = if (generated > 0) (totalMs - ttft).toDouble() / generated else null,
                    promptTokens = 256,
                    generatedTokens = generated,
                    effectiveConfig = request.config,
                    warnings = listOf("FakeEngine metrics are fabricated"),
                )
                session.metrics = metrics
                onEvent(
                    EngineEvent.Done(
                        GenerateResult(
                            text = words.take(generated).joinToString(" "),
                            promptTokens = 256,
                            generatedTokens = generated,
                            metrics = metrics,
                        ),
                    ),
                )
            } catch (t: Throwable) {
                onEvent(
                    EngineEvent.Error(
                        if (t is kotlinx.coroutines.CancellationException) {
                            EngineException.Cancelled()
                        } else {
                            EngineException.GenerateFailed(t.message ?: "fake generate failed", t)
                        },
                    ),
                )
            } finally {
                session.generating.set(false)
                scope.cancel()
            }
        }
        return FakeJob(job)
    }

    override suspend fun reset(handle: SessionHandle) {
        val session = handle as? FakeSession ?: return
        if (session.generating.get()) {
            throw EngineException.InvalidState("cannot reset while generating")
        }
        session.metrics = session.metrics?.copy()
    }

    override suspend fun unload(handle: SessionHandle) {
        val session = handle as? FakeSession ?: return
        // Contract: unload during generate must not crash. Fail explicitly.
        if (session.generating.get()) {
            throw EngineException.InvalidState("unload called while generating; cancel first")
        }
        if (session.closed.compareAndSet(false, true)) {
            session.metrics = null
        }
    }

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? =
        (handle as? FakeSession)?.metrics

    private companion object {
        val FAKE_WORDS = listOf(
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing",
            "elit", "sed", "do", "eiusmod", "tempor", "incididunt", "ut", "labore",
        )
    }
}

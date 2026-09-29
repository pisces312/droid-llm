package io.github.pisces312.droidllm.engineapi

/**
 * Sink for engine-level diagnostics (see `docs/mnn.md` §2 for what is worth logging).
 *
 * Lives in `engine-api` rather than `core:common` because adapters and the
 * decorator below are the producers, and `engine-api` must not depend on the
 * app module. The app wires the concrete sink (`DiagLogger`) at startup.
 */
interface EngineLogSink {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}

/** No-op sink used when nothing is wired (unit tests, previews). */
object NoopEngineLogSink : EngineLogSink {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, error: Throwable?) = Unit
    override fun e(tag: String, message: String, error: Throwable?) = Unit
}

/**
 * Process-wide sink holder.
 *
 * Each `:engine:*` module binds its own adapter `@IntoSet`, and Hilt gives the
 * app a `Set<LlmEngine>`. Wrapping the set from the app module would need the
 * set injected into the module that produces it, so instead the app installs
 * the real sink here once in `Application.onCreate` and every adapter module
 * wraps itself with [wrap]. The decorator looks up [current] on every call, so
 * engines created before the install still log once the sink exists. Undefined
 * (no-op) until then, which is exactly what unit tests and previews want.
 */
object EngineLogging {
    @Volatile
    private var sink: EngineLogSink = NoopEngineLogSink

    fun install(sink: EngineLogSink) {
        this.sink = sink
    }

    fun current(): EngineLogSink = sink

    /** Decorates [engine], resolving the installed sink on every call. */
    fun wrap(engine: LlmEngine): LlmEngine = LoggingLlmEngine(engine)
}

/**
 * Wraps an [LlmEngine], recording load / generate / unload with the data needed
 * to diagnose "wrong output on real hardware" without a PC attached.
 *
 * Rationale for a decorator instead of editing four adapters: the interesting
 * record is flat across engines — request shape, template-visible prompt length,
 * token count, timing, thrown exception — and adapters already compute the
 * engine-specific numbers. Logging at this seam covers chat, the local API
 * server and the benchmark runner in one place.
 *
 * [sink] is a provider, not a captured instance: Hilt may build the engine set
 * before `Application.onCreate` installs the real sink, and capturing `Noop`
 * at construction time would silence the whole process for its lifetime.
 */
class LoggingLlmEngine(
    private val delegate: LlmEngine,
    private val sink: () -> EngineLogSink = { EngineLogging.current() },
) : LlmEngine {

    override val id: EngineId get() = delegate.id
    override val displayName: String get() = delegate.displayName
    override val version: EngineVersion get() = delegate.version

    /**
     * Must be forwarded: every adapter is wrapped by this decorator before Hilt
     * hands it to the app, so forgetting this silently drops the engine's own
     * defaults and the app falls back to `InferenceConfig`'s placeholder values.
     */
    override val defaults: EngineDefaults get() = delegate.defaults

    private val tag get() = "engine/${delegate.id.name.lowercase()}"

    /**
     * Every write goes through here.
     *
     * A logger must never be able to change engine behaviour: `generate` calls
     * its sink on the event path, so an exception thrown by a sink would
     * otherwise surface as a fake inference failure (or, from inside a coroutine,
     * as an unrelated crash). Swallowing here keeps the decorator inert.
     */
    private inline fun safe(block: (EngineLogSink) -> Unit) {
        try {
            block(sink())
        } catch (_: Throwable) {
            // Intentionally ignored — see KDoc.
        }
    }

    override suspend fun probe(probeContext: ProbeContext): Availability {
        val result = delegate.probe(probeContext)
        safe { it.i(tag, "probe -> ${describe(result)}") }
        return result
    }

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle {
        safe {
            it.i(
                tag,
                "load start model=${model.displayName} id=${model.id} " +
                    "format=${model.formatHint ?: "-"} location=${describeLocation(model.location)} " +
                    "config=${describeConfig(config)}",
            )
        }
        val startedAt = System.nanoTime()
        return try {
            val handle = delegate.load(model, config)
            safe {
                it.i(
                    tag,
                    "load ok model=${model.displayName} in ${elapsedMs(startedAt)}ms " +
                        "handle=${handle.javaClass.simpleName}",
                )
            }
            handle
        } catch (t: Throwable) {
            safe {
                it.e(
                    tag,
                    "load FAILED model=${model.displayName} after ${elapsedMs(startedAt)}ms",
                    t,
                )
            }
            throw t
        }
    }

    override fun generate(
        handle: SessionHandle,
        request: GenerateRequest,
        onEvent: (EngineEvent) -> Unit,
    ): GenerateJob {
        val config = request.config
        // The template-visible prompt is what actually reaches the model; the
        // message list alone hid the "short prompt -> immediate EOS" bug. Log
        // the role/length shape, never the raw user text (it is the user's data).
        val shape = request.messages.joinToString(",") { "${it.role.name.lowercase()}:${it.content.length}" }
        safe {
            it.i(
                tag,
                "generate start model=${handle.modelId} messages=${request.messages.size} " +
                    "promptChars=[$shape] maxNew=${config.maxNewTokens} " +
                    "temp=${config.temperature} topK=${config.topK} topP=${config.topP} " +
                    "threads=${config.threads} backend=${config.backend} " +
                    "thinking=${config.enableThinking} system=${config.systemPrompt?.length ?: 0}chars",
            )
        }
        val startedAt = System.nanoTime()
        var firstTokenAt = 0L
        var tokens = 0
        val text = StringBuilder()

        return delegate.generate(handle, request) { event ->
            when (event) {
                is EngineEvent.Token -> {
                    if (tokens == 0) {
                        firstTokenAt = System.nanoTime()
                        // First token is the single most diagnostic datum: EOS
                        // here means the model rejected the prompt outright.
                        safe {
                            it.i(
                                tag,
                                "first token after ${elapsedMs(startedAt)}ms " +
                                    "text=${quote(event.text)}",
                            )
                        }
                    }
                    tokens++
                    text.append(event.text)
                }

                is EngineEvent.Done -> {
                    val m = event.result.metrics
                    // The summary line is the one that explains a bad run, so
                    // it is written even though it is the longest.
                    safe { s ->
                        val summary =
                            "generate done tokens=$tokens promptTokens=${m.promptTokens} " +
                                "generatedTokens=${m.generatedTokens} " +
                                "ttft=${m.ttftMs ?: (firstTokenAt - startedAt) / 1_000_000}ms " +
                                "prefill=${m.prefillTps?.let { fmt(it) } ?: "-"}tps " +
                                "decode=${m.decodeTps?.let { fmt(it) } ?: "-"}tps " +
                                "total=${elapsedMs(startedAt)}ms " +
                                "rss=${m.rssMbPeak ?: -1}MB " +
                                "warnings=${m.warnings.size}" +
                                (if (m.warnings.isEmpty()) "" else " " + m.warnings.joinToString("; "))
                        s.i(tag, summary)
                    }
                    if (tokens == 1) {
                        // Seen in the emulator and on-device for LFM2-350M:
                        // a single token that is a stop token yields a blank bubble.
                        safe {
                            it.w(
                                tag,
                                "only ONE token produced (text=${quote(text.toString())}); " +
                                    "if the bubble is blank this is an immediate-EOS, " +
                                    "usually a missing system prompt or an over-short prompt",
                            )
                        }
                    } else if (m.warnings.isNotEmpty()) {
                        m.warnings.forEach { warning ->
                            safe { it.w(tag, "adapter warning: $warning") }
                        }
                    }
                }

                is EngineEvent.Error -> {
                    safe {
                        it.e(
                            tag,
                            "generate error after ${elapsedMs(startedAt)}ms tokens=$tokens " +
                                "type=${event.cause.javaClass.simpleName} msg=${event.cause.message}",
                            event.cause,
                        )
                    }
                }
            }
            onEvent(event)
        }.also { job ->
            safe { it.d(tag, "generate job active=${job.isActive}") }
        }
    }

    override suspend fun reset(handle: SessionHandle) {
        safe { it.i(tag, "reset model=${handle.modelId}") }
        try {
            delegate.reset(handle)
        } catch (t: Throwable) {
            safe { it.e(tag, "reset FAILED model=${handle.modelId}", t) }
            throw t
        }
    }

    override suspend fun unload(handle: SessionHandle) {
        safe { it.i(tag, "unload model=${handle.modelId}") }
        try {
            delegate.unload(handle)
        } catch (t: Throwable) {
            safe { it.e(tag, "unload FAILED model=${handle.modelId}", t) }
            throw t
        }
    }

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? = delegate.lastMetrics(handle)

    // ---- formatting helpers ------------------------------------------------

    private fun describeConfig(config: InferenceConfig): String =
        "maxNew=${config.maxNewTokens} temp=${config.temperature} topK=${config.topK} " +
            "topP=${config.topP} threads=${config.threads} backend=${config.backend} " +
            "thinking=${config.enableThinking}"

    private fun describeLocation(location: ModelLocation): String = when (location) {
        is ModelLocation.FilePath -> "file:${location.path}"
        is ModelLocation.SafUri -> "saf:${location.uri}"
        is ModelLocation.AppPrivate -> "app:${location.relativePath}"
    }

    private fun describe(result: Availability): String = when (result) {
        is Availability.Available -> "Available"
        is Availability.MissingDependency -> "MissingDependency(${result.detail})"
        is Availability.UnsupportedSoc -> "UnsupportedSoc(${result.detail})"
        is Availability.ModelNotConfigured -> "ModelNotConfigured(${result.detail})"
        is Availability.InvalidModel -> "InvalidModel(${result.detail})"
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    /** Truncated, escaped preview: log lines must stay single-line and short. */
    private fun quote(raw: String, max: Int = 60): String {
        val flat = raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
        return if (flat.length <= max) "\"$flat\"" else "\"${flat.take(max)}…\"(${raw.length} chars)"
    }
}

package io.github.pisces312.droidllm.engineapi

/**
 * Identity of an on-device inference engine backend.
 */
enum class EngineId {
    /** UI / contract development only. */
    FAKE,
    LITERT,
    MNN,
    GENIE,
    LLAMACPP,
}

/**
 * Human-readable engine name — **the single source of truth for every UI surface**
 * (chat picker, models list, benchmark table, error messages).
 *
 * Each adapter MUST return this from [LlmEngine.displayName] instead of hard-coding its own
 * string; otherwise the screens drift apart (e.g. `LLAMACPP` in one place, `MNN 3.6.1 #c0461933`
 * in another). [LlmEngine.labelledName] builds version+commit on top of it.
 *
 * Do **not** use this for persistence or directory names — those use [EngineId.name]
 * (see `ModelsViewModel.engineDir`), which must stay stable across releases.
 */
val EngineId.displayName: String
    get() = when (this) {
        // Test-only double; mirrors `FakeEngine.displayName`.
        EngineId.FAKE -> "FakeEngine"
        EngineId.LITERT -> "LiteRT-LM"
        EngineId.MNN -> "MNN"
        EngineId.GENIE -> "Genie"
        EngineId.LLAMACPP -> "llama.cpp"
    }

/**
 * Inverse of the persisted form: resolves a stored [EngineId.name] back to its enum
 * (case-insensitive), or null when the string was written by a different release.
 *
 * UI that reads persisted rows (e.g. the benchmark history list) must go through this and
 * then [displayName] — never print the raw stored string.
 */
fun engineIdFromStorage(raw: String?): EngineId? =
    raw?.let { stored -> EngineId.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } }

/**
 * Build provenance of an engine backend.
 *
 * Every part is optional: sources that cannot be resolved at build time
 * (e.g. a prebuilt Maven artifact with no source tree) leave it null, and the
 * UI omits that part instead of showing a fabricated value.
 */
data class EngineVersion(
    val version: String? = null,
    val commit: String? = null,
) {
    val isKnown: Boolean get() = !version.isNullOrBlank() || !commit.isNullOrBlank()

    /** `"MNN 3.6.1 (#c0461933)"`; falls back to the bare name when nothing is known. */
    fun label(name: String): String {
        val parts = listOfNotNull(
            version?.takeIf { it.isNotBlank() },
            commit?.takeIf { it.isNotBlank() }?.let { "#$it" },
        )
        return if (parts.isEmpty()) name else "$name ${parts.joinToString(" ")}"
    }
}

/**
 * Compute backend requested by the caller.
 *
 * Engine adapters MUST reject unsupported values with
 * [EngineException.UnsupportedBackend] and MUST NOT silently fall back to another backend.
 */
enum class Backend {
    CPU,
    GPU,
    OPENCL,
    NPU_HTP,
    AUTO,
}

/**
 * Sampling / execution knobs. Fields that do not apply to a given engine are
 * ignored with a warning and the adapter reports the effective values via
 * [EngineMetrics.effectiveConfig].
 */
data class InferenceConfig(
    val maxNewTokens: Int = 128,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val threads: Int = 4,
    val backend: Backend = Backend.AUTO,
    val seed: Long? = null,
    /**
     * System instruction for this turn, or null to send none.
     *
     * Resolved by the app layer, which prepends it as the first
     * [ChatMessage] with role `system` — see [GenerateRequest.messages].
     * Adapters must NOT prepend it again. Prepend rather than append: chat
     * templates commonly only render a system block when the **first** message
     * carries that role (see `docs/mnn.md`).
     */
    val systemPrompt: String? = null,
)

enum class ChatRole { SYSTEM, USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

data class GenerateRequest(
    val messages: List<ChatMessage>,
    val config: InferenceConfig,
)

/**
 * Unified model locator. Concrete engines decide how to resolve [location]
 * to a real file/directory.
 */
sealed class ModelLocation {
    data class FilePath(val path: String) : ModelLocation()
    data class SafUri(val uri: String) : ModelLocation()
    data class AppPrivate(val relativePath: String) : ModelLocation()
}

data class LocalModel(
    val id: String,
    val engineId: EngineId,
    val displayName: String,
    val location: ModelLocation,
    val formatHint: String? = null,
    val fileSizeBytes: Long? = null,
    val quantHint: String? = null,
)

data class GenerateResult(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val metrics: EngineMetrics,
)

/**
 * Timing and resource metrics.
 *
 * Timing contract (must be identical across adapters):
 * - TTFT: from generate() request emission to the first token callback.
 *   Model load and chat-template formatting are NOT included.
 * - prefill_tps: promptTokens / (ttftMs / 1000) when promptTokens > 0.
 * - decode_tps: (generatedTokens - 1) / decodeDurationSeconds when generatedTokens > 1.
 */
data class EngineMetrics(
    val loadMs: Long? = null,
    val ttftMs: Long? = null,
    val prefillTps: Double? = null,
    val decodeTps: Double? = null,
    val perTokenMsP50: Double? = null,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val rssMbLoad: Long? = null,
    val rssMbPeak: Long? = null,
    /** Effective config after adapter-side clamping / ignoring inapplicable fields. */
    val effectiveConfig: InferenceConfig? = null,
    /** Free-form adapter notes, e.g. "threads ignored for GENIE". */
    val warnings: List<String> = emptyList(),
)

sealed class EngineEvent {
    data class Token(val text: String, val index: Int) : EngineEvent()
    data class Done(val result: GenerateResult) : EngineEvent()
    data class Error(val cause: EngineException) : EngineEvent()
}

sealed class Availability {
    data object Available : Availability()
    data class MissingDependency(val detail: String) : Availability()
    data class UnsupportedSoc(val detail: String) : Availability()
    data class ModelNotConfigured(val detail: String) : Availability()
    data class InvalidModel(val detail: String) : Availability()
}

sealed class EngineException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class LoadFailed(message: String, cause: Throwable? = null) : EngineException(message, cause)
    class GenerateFailed(message: String, cause: Throwable? = null) : EngineException(message, cause)
    class Cancelled(message: String = "cancelled") : EngineException(message)
    class UnsupportedBackend(message: String) : EngineException(message)
    class InvalidState(message: String) : EngineException(message)
}

/** Cancellable handle returned by [LlmEngine.generate]. */
interface GenerateJob {
    fun cancel()
    val isActive: Boolean
}

/** Opaque session produced by [LlmEngine.load]. */
interface SessionHandle {
    val engineId: EngineId
    val modelId: String
    val isClosed: Boolean
}

data class ProbeContext(
    val socModel: String?,
    val sdkInt: Int,
    val hasOpenCl: Boolean,
    val hasCdspRpc: Boolean,
    val totalRamMb: Long,
    val availableStorageMb: Long,
)

/**
 * Unified on-device LLM engine contract.
 *
 * Thread-safety contract (normative):
 * 1. Adapters MUST reject [Backend] values they do not support via
 *    [EngineException.UnsupportedBackend] — never silent fallback.
 * 2. [InferenceConfig] fields that do not apply MUST be ignored with a warning
 *    recorded in [EngineMetrics.warnings]; effective values go into
 *    [EngineMetrics.effectiveConfig].
 * 3. On a single [SessionHandle], `generate` and `unload`/`reset` are mutually
 *    exclusive. At most one `generate` may run per session. `unload` during an
 *    active generate must either block until generation finishes or fail with
 *    [EngineException.InvalidState]; it MUST NOT crash.
 */
interface LlmEngine {
    val id: EngineId
    val displayName: String

    /**
     * Library/runtime provenance compiled into the module. Shown next to
     * [displayName] in the chat picker, benchmark rows and exported JSON.
     */
    val version: EngineVersion get() = EngineVersion()

    suspend fun probe(probeContext: ProbeContext): Availability

    suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle

    fun generate(
        handle: SessionHandle,
        request: GenerateRequest,
        onEvent: (EngineEvent) -> Unit,
    ): GenerateJob

    suspend fun reset(handle: SessionHandle)

    suspend fun unload(handle: SessionHandle)

    fun lastMetrics(handle: SessionHandle): EngineMetrics?
}

/** [LlmEngine.displayName] with version/commit appended, e.g. `MNN 3.6.1 #c0461933`. */
val LlmEngine.labelledName: String get() = version.label(displayName)

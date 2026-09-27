package io.github.pisces312.droidllm.engineapi

/**
 * Which [InferenceConfig] knobs a given engine actually honors.
 * UI greys out inapplicable fields with a short note (UI_DESIGN §5.1 / DESIGN §1.2).
 * Adapters must stay consistent: ignore + warn, never silent different meaning.
 */
enum class ConfigField {
    TEMPERATURE,
    TOP_K,
    TOP_P,
    THREADS,
    SEED,
    BACKEND,
    MAX_NEW_TOKENS,
}

object ConfigApplicability {

    fun isApplicable(engineId: EngineId, field: ConfigField): Boolean = when (engineId) {
        EngineId.FAKE -> true
        EngineId.LITERT -> field != ConfigField.THREADS && field != ConfigField.SEED
        EngineId.MNN -> field != ConfigField.SEED
        EngineId.GENIE -> field != ConfigField.THREADS && field != ConfigField.SEED
        EngineId.LLAMACPP -> true
    }

    /** Short Chinese note shown at 12sp under a greyed field. */
    fun note(engineId: EngineId, field: ConfigField): String? {
        if (isApplicable(engineId, field)) return null
        return when (field) {
            ConfigField.THREADS -> when (engineId) {
                EngineId.LITERT -> "LiteRT 自管线程池，此参数不生效"
                EngineId.GENIE -> "Genie 走 HTP，线程数不适用"
                else -> "此引擎不支持线程数"
            }
            ConfigField.SEED -> "此引擎不支持固定随机种子"
            else -> "此引擎不支持该参数"
        }
    }

    /** Backends the engine accepts (DESIGN §1.2 mapping). Others are rejected. */
    fun supportedBackends(engineId: EngineId): List<Backend> = when (engineId) {
        EngineId.FAKE -> Backend.entries
        EngineId.LITERT -> listOf(Backend.CPU, Backend.GPU, Backend.NPU_HTP, Backend.AUTO)
        EngineId.MNN -> listOf(Backend.CPU, Backend.GPU, Backend.OPENCL, Backend.NPU_HTP, Backend.AUTO)
        EngineId.GENIE -> listOf(Backend.NPU_HTP, Backend.AUTO)
        EngineId.LLAMACPP -> listOf(Backend.CPU, Backend.GPU, Backend.OPENCL, Backend.NPU_HTP, Backend.AUTO)
    }

    fun backendNote(engineId: EngineId): String? = when (engineId) {
        EngineId.GENIE -> "Genie 仅支持 NPU/HTP"
        EngineId.LITERT -> "LiteRT 不支持 OPENCL"
        else -> null
    }
}

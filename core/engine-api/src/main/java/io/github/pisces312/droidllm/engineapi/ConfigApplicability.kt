package io.github.pisces312.droidllm.engineapi

import android.content.Context
import io.github.pisces312.droidllm.engineapi.R

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
    THINKING,
}

object ConfigApplicability {

    fun isApplicable(engineId: EngineId, field: ConfigField): Boolean = when (engineId) {
        EngineId.FAKE -> true
        EngineId.LITERT -> field != ConfigField.THREADS && field != ConfigField.SEED
        EngineId.MNN -> field != ConfigField.SEED
        EngineId.GENIE -> field != ConfigField.THREADS && field != ConfigField.SEED
        EngineId.LLAMACPP -> true
    }

    /** Short note shown at 12sp under a greyed field. */
    fun note(context: Context, engineId: EngineId, field: ConfigField): String? {
        if (isApplicable(engineId, field)) return null
        return when (field) {
            ConfigField.THREADS -> when (engineId) {
                EngineId.LITERT -> context.getString(R.string.applicability_threads_litert)
                EngineId.GENIE -> context.getString(R.string.applicability_threads_genie)
                else -> context.getString(R.string.applicability_threads_other)
            }
            ConfigField.SEED -> context.getString(R.string.applicability_seed)
            ConfigField.THINKING -> context.getString(R.string.applicability_thinking)
            else -> context.getString(R.string.applicability_generic)
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

    fun backendNote(context: Context, engineId: EngineId): String? = when (engineId) {
        EngineId.GENIE -> context.getString(R.string.applicability_backend_genie)
        EngineId.LITERT -> context.getString(R.string.applicability_backend_litert)
        else -> null
    }
}

package io.github.pisces312.droidllm.engineapi

/**
 * Whether a model can flip reasoning / "Thinking" on and off between turns.
 *
 * The switch is per-message by design (UI_REVIEW.md §3.2 模式 3): it lives next to
 * the composer so the user can change it without leaving the transcript, the same
 * place MnnLlmChat puts `btn_toggle_thinking`.
 *
 * Detection is intentionally conservative — showing a toggle that does nothing is
 * worse than hiding it. Two signals, either is enough:
 *
 * 1. Model name suggests a reasoning family (Qwen3 / thinking / R1 / …).
 * 2. Engine-native template flag (MNN `jinja.chat_template` contains `enable_thinking`).
 */
object ThinkingSupport {

    private val NAME_HINTS = listOf(
        "qwen3", "qwen-3", "qwen 3",
        "thinking", "thinker",
        "r1", "deepseek-r1",
        "lfm2.*think",
    )

    /** Cheap name check; used for every engine and as the MNN fallback. */
    fun byName(displayName: String): Boolean {
        val n = displayName.lowercase()
        return NAME_HINTS.any { hint ->
            if (hint.contains(".*")) {
                Regex(hint).containsMatchIn(n)
            } else {
                n.contains(hint)
            }
        }
    }

    /**
     * Resolve [model] against both signals. Callers that can read the model dir
     * (e.g. MNN's `llm_config.json`) should pass [templateHasEnableThinking];
     * leave it null when the file is unknown and only the name is checked.
     */
    fun supports(
        model: LocalModel,
        templateHasEnableThinking: Boolean? = null,
    ): Boolean {
        if (templateHasEnableThinking == true) return true
        return byName(model.displayName)
    }
}

package io.github.pisces312.droidllm.common.model

import io.github.pisces312.droidllm.engineapi.EngineDefaults
import io.github.pisces312.droidllm.engineapi.InferenceConfig

/**
 * Single implementation of the two-layer sampling chain documented in
 * `docs/MODEL_PARAMS.md`: **per-model overlay → engine `EngineDefaults`**.
 * There is deliberately no app-wide sampling layer — an app-level `0.7 / 40`
 * inherited by a model family that needs `1.0 / 64` is how the LiteRT greeting
 * loop shipped (`docs/litert.md` §3.3).
 *
 * All three consumers (chat, API server, benchmark) must go through here so the
 * chains cannot drift (CODE_REVIEW B1).
 */
object ConfigResolver {

    /**
     * @param overlay per-model pin; null fields inherit [defaults].
     * @param appSystemPrompt the one app-level value — a prompt, not a sampling
     *   knob. Used only when [ModelParamsOverride.systemPrompt] is null; blank
     *   becomes null ("send none").
     * @param maxNewTokensOverride caller-forced token cap (benchmark cases) that
     *   replaces both the overlay and the engine default when non-null.
     * @param seed null means "engine decides" (random), matching the contract.
     * @param enableThinking per-turn knob; not part of the two-layer chain.
     */
    fun resolve(
        defaults: EngineDefaults,
        overlay: ModelParamsOverride? = null,
        appSystemPrompt: String? = null,
        maxNewTokensOverride: Int? = null,
        seed: Long? = null,
        enableThinking: Boolean = true,
    ): InferenceConfig {
        val o = overlay ?: ModelParamsOverride()
        return InferenceConfig(
            maxNewTokens = maxNewTokensOverride ?: o.maxNewTokens ?: defaults.maxNewTokens,
            temperature = o.temperature ?: defaults.temperature,
            topK = o.topK ?: defaults.topK,
            topP = o.topP ?: defaults.topP,
            threads = o.threads ?: defaults.threads,
            backend = ModelParamsOverride.backendOf(o.backend) ?: defaults.backend,
            seed = seed,
            systemPrompt = o.systemPrompt ?: appSystemPrompt?.takeIf { it.isNotBlank() },
            enableThinking = enableThinking,
        )
    }
}

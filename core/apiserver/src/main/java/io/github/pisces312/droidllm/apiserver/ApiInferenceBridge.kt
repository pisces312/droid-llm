package io.github.pisces312.droidllm.apiserver

/**
 * Bridge between the HTTP layer and the LLM engines. Implemented in `:app`
 * against `LlmEngine` / `ModelPathStore` so this module stays engine-agnostic.
 */
data class ApiModelInfo(
    val id: String,
    val displayName: String,
    val engineId: String,
)

data class ApiChatMessage(
    val role: String,
    val content: String,
)

data class ApiChatParams(
    val model: String?,
    val messages: List<ApiChatMessage>,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = false,
)

data class ApiGenerateResult(
    val modelId: String,
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
)

/**
 * Streaming callbacks. Return false from [onToken] to request cancellation
 * (e.g. the HTTP client disconnected). [onToken] is invoked on the engine
 * callback thread and must not block.
 */
interface ApiInferenceBridge {
    suspend fun listModels(): List<ApiModelInfo>

    /**
     * Run one chat completion through the engine. Single-flight: callers are
     * serialized by [RequestQueueManager]; this method must not start a second
     * generate on the same session concurrently.
     */
    suspend fun generate(
        params: ApiChatParams,
        onToken: (String) -> Boolean = { true },
    ): ApiGenerateResult

    /** Best-effort cancel of the in-flight generate, if any. */
    fun cancelActive()
}

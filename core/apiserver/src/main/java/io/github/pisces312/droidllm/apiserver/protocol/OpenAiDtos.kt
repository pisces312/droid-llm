package io.github.pisces312.droidllm.apiserver.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenAiChatRequest(
    val model: String? = null,
    val messages: List<OpenAiMessage>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
)

/**
 * `content` is kept as [JsonElement] so a non-string (image array) can be
 * rejected with a clear 400 instead of a parse failure.
 */
@Serializable
data class OpenAiMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class OpenAiErrorBody(
    val error: OpenAiError,
)

@Serializable
data class OpenAiError(
    val message: String,
    val type: String = "invalid_request_error",
    val code: String? = null,
)

@Serializable
data class OpenAiModelList(
    val `object`: String = "list",
    val data: List<OpenAiModel>,
)

@Serializable
data class OpenAiModel(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0L,
    val owned_by: String = "droid-llm",
)

@Serializable
data class OpenAiChatCompletion(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage,
)

@Serializable
data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiAssistantMessage,
    @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class OpenAiAssistantMessage(
    val role: String = "assistant",
    val content: String,
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class OpenAiChatChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<OpenAiChunkChoice>,
)

@Serializable
data class OpenAiChunkChoice(
    val index: Int = 0,
    val delta: OpenAiChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

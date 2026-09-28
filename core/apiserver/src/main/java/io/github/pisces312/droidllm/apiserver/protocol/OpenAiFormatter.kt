package io.github.pisces312.droidllm.apiserver.protocol

import kotlinx.serialization.json.Json

object OpenAiFormatter {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun chatCompletion(
        id: String,
        created: Long,
        model: String,
        content: String,
        promptTokens: Int,
        completionTokens: Int,
    ): String = json.encodeToString(
        OpenAiChatCompletion.serializer(),
        OpenAiChatCompletion(
            id = id,
            created = created,
            model = model,
            choices = listOf(
                OpenAiChoice(message = OpenAiAssistantMessage(content = content)),
            ),
            usage = OpenAiUsage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
            ),
        ),
    )

    fun chunk(
        id: String,
        created: Long,
        model: String,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): String = json.encodeToString(
        OpenAiChatChunk.serializer(),
        OpenAiChatChunk(
            id = id,
            created = created,
            model = model,
            choices = listOf(
                OpenAiChunkChoice(
                    delta = OpenAiChunkDelta(role = role, content = content),
                    finishReason = finishReason,
                ),
            ),
        ),
    )

    fun models(data: List<OpenAiModel>): String =
        json.encodeToString(OpenAiModelList.serializer(), OpenAiModelList(data = data))

    fun error(message: String, type: String = "invalid_request_error", code: String? = null): String =
        json.encodeToString(
            OpenAiErrorBody.serializer(),
            OpenAiErrorBody(OpenAiError(message = message, type = type, code = code)),
        )
}

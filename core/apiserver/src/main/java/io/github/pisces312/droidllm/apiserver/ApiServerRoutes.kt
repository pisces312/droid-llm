package io.github.pisces312.droidllm.apiserver

import io.github.pisces312.droidllm.apiserver.protocol.OpenAiFormatter
import io.github.pisces312.droidllm.apiserver.protocol.OpenAiModel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.event.Level

private val requestJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Ktor module: OpenAI-compatible `/v1/models` + `/v1/chat/completions`. */
fun Application.apiModule(
    config: ApiServerConfig,
    bridge: ApiInferenceBridge,
    queue: RequestQueueManager = RequestQueueManager(),
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }

    if (config.corsEnabled) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("x-api-key")
            val origins = config.corsOrigins
            if (origins.isNotBlank()) {
                origins.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { origin ->
                        allowHost(origin.removePrefix("http://").removePrefix("https://"))
                    }
            } else {
                anyHost()
                allowCredentials = true
            }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.local.uri.startsWith("/v1/") }
    }

    routing {
        get("/") {
            call.respondText(
                "droid-llm OpenAI-compatible API\n" +
                    "POST /v1/chat/completions\n" +
                    "GET  /v1/models\n",
                contentType = ContentType.Text.Plain,
            )
        }

        get("/v1/models") {
            if (!call.isAuthorized(config)) {
                call.respondUnauthorized()
                return@get
            }
            val models = bridge.listModels().map {
                OpenAiModel(id = it.id, owned_by = it.engineId)
            }
            call.respondText(
                OpenAiFormatter.models(models),
                contentType = ContentType.Application.Json,
            )
        }

        post("/v1/chat/completions") {
            if (!call.isAuthorized(config)) {
                call.respondUnauthorized()
                return@post
            }
            handleChatCompletions(call, bridge, queue)
        }
    }
}

private fun ApplicationCall.isAuthorized(config: ApiServerConfig): Boolean {
    if (!config.authEnabled) return true
    if (config.apiKey.isBlank()) return true
    val bearer = request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.trim()
    val xApiKey = request.headers["x-api-key"]?.trim()
    return bearer == config.apiKey || xApiKey == config.apiKey
}

private suspend fun ApplicationCall.respondUnauthorized() {
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respondText(
        text = OpenAiFormatter.error(
            message = "Invalid API key",
            type = "authentication_error",
            code = "invalid_api_key",
        ),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
    )
}

private suspend fun ApplicationCall.respondJsonError(
    status: HttpStatusCode,
    message: String,
    type: String = "invalid_request_error",
    code: String? = null,
) {
    respondText(
        text = OpenAiFormatter.error(message, type, code),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

/** Map raw JSON body → [ApiChatParams]; null when structure is invalid. */
internal fun parseChatRequest(raw: String): ApiChatParams? {
    val root = runCatching { requestJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return null
    val messagesEl = root["messages"] as? JsonArray ?: return null
    val messages = messagesEl.mapNotNull { item ->
        val o = item as? JsonObject ?: return null
        val role = (o["role"] as? JsonPrimitive)?.content ?: return null
        val contentEl = o["content"] ?: return null
        val content = when {
            contentEl is JsonPrimitive -> contentEl.content
            else -> return null // image / structured content not supported
        }
        ApiChatMessage(role = role, content = content)
    }
    if (messages.isEmpty()) return null
    return ApiChatParams(
        model = (root["model"] as? JsonPrimitive)?.content,
        messages = messages,
        temperature = (root["temperature"] as? JsonPrimitive)?.content?.toFloatOrNull(),
        topP = (root["top_p"] as? JsonPrimitive)?.content?.toFloatOrNull(),
        maxTokens = (root["max_tokens"] as? JsonPrimitive)?.content?.toIntOrNull(),
        stream = (root["stream"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
    )
}

private suspend fun handleChatCompletions(
    call: ApplicationCall,
    bridge: ApiInferenceBridge,
    queue: RequestQueueManager,
) {
    val raw = call.receiveText()
    val params = parseChatRequest(raw)
    if (params == null) {
        call.respondJsonError(
            HttpStatusCode.BadRequest,
            "messages must be an array of {role, content: string}",
        )
        return
    }
    if (params.messages.isEmpty()) {
        call.respondJsonError(HttpStatusCode.BadRequest, "messages must not be empty")
        return
    }

    val completionId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}"
    val created = System.currentTimeMillis() / 1000
    val modelName = params.model ?: "default"

    if (params.stream) {
        handleStream(call, bridge, queue, params, completionId, created, modelName)
    } else {
        try {
            val result = queue.submit(completionId) {
                bridge.generate(params, onToken = { true })
            }
            call.respondText(
                OpenAiFormatter.chatCompletion(
                    id = completionId,
                    created = created,
                    model = result.modelId,
                    content = result.text,
                    promptTokens = result.promptTokens,
                    completionTokens = result.generatedTokens,
                ),
                contentType = ContentType.Application.Json,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            call.respondJsonError(
                HttpStatusCode.InternalServerError,
                e.message ?: "generation failed",
                type = "server_error",
            )
        }
    }
}

/**
 * SSE writer decoupled from the engine callback via a Channel: tokens are
 * produced on the engine thread and consumed on the Ktor writer thread.
 * A failed write (client disconnect) closes the channel and cancels generate.
 */
private suspend fun handleStream(
    call: ApplicationCall,
    bridge: ApiInferenceBridge,
    queue: RequestQueueManager,
    params: ApiChatParams,
    completionId: String,
    created: Long,
    modelName: String,
) {
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        val out = Channel<String>(Channel.UNLIMITED)
        val producer = kotlinx.coroutines.coroutineScope {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                queue.submit(completionId) {
                    bridge.generate(params) { token ->
                        val payload = OpenAiFormatter.chunk(
                            id = completionId,
                            created = created,
                            model = modelName,
                            content = token,
                        )
                        out.trySend("data: $payload\n\n").isSuccess
                    }
                }
                out.send(
                    "data: " + OpenAiFormatter.chunk(
                        id = completionId,
                        created = created,
                        model = modelName,
                        role = "assistant",
                    ) + "\n\n",
                )
                out.send(
                    "data: " + OpenAiFormatter.chunk(
                        id = completionId,
                        created = created,
                        model = modelName,
                        finishReason = "stop",
                    ) + "\n\n",
                )
                out.send("data: [DONE]\n\n")
            } catch (_: CancellationException) {
                // cancelled by client or service stop
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                runCatching {
                    out.send("data: ${OpenAiFormatter.error(msg, "server_error")}\n\n")
                    out.send("data: [DONE]\n\n")
                }
                } finally {
                    out.close()
                }
            }
            try {
                for (line in out) {
                    write(line)
                    flush()
                }
            } catch (_: Exception) {
                bridge.cancelActive()
                job.cancel()
            }
            job.join()
        }
    }
}

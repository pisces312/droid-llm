package io.github.pisces312.droidllm.apiserver

/**
 * Runtime settings for the local OpenAI-compatible API server.
 * Persisted by the app layer (DataStore); this module only reads the snapshot.
 */
data class ApiServerConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND,
    val authEnabled: Boolean = true,
    val apiKey: String = "",
    val corsEnabled: Boolean = false,
    val corsOrigins: String = "",
) {
    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_BIND = "127.0.0.1"

        fun generateApiKey(length: Int = 16): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(length) { repeat(length) { append(chars.random()) } }
        }
    }
}

/** App-layer source of [ApiServerConfig] (DataStore / SharedPreferences). */
interface ApiServerConfigSource {
    suspend fun current(): ApiServerConfig
}

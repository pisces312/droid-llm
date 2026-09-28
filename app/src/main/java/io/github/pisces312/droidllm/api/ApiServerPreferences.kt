package io.github.pisces312.droidllm.api

import io.github.pisces312.droidllm.apiserver.ApiServerConfig
import io.github.pisces312.droidllm.apiserver.ApiServerConfigSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [ApiServerConfigSource]. Uses SharedPreferences-like
 * DataStore keys dedicated to the API server so chat settings stay untouched.
 */
@Singleton
class ApiServerPreferences @Inject constructor(
    private val store: ApiServerConfigStore,
) : ApiServerConfigSource {

    override suspend fun current(): ApiServerConfig = store.read()

    suspend fun update(config: ApiServerConfig) = store.write(config)

    suspend fun regenerateApiKey(): ApiServerConfig {
        val cur = store.read()
        val next = cur.copy(apiKey = ApiServerConfig.generateApiKey())
        store.write(next)
        return next
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.write(store.read().copy(enabled = enabled))
    }
}

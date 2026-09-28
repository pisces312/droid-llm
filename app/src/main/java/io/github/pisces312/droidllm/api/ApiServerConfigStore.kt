package io.github.pisces312.droidllm.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.apiserver.ApiServerConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.apiServerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "droid_api_server",
)

@Singleton
class ApiServerConfigStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val port = intPreferencesKey("port")
        val bind = stringPreferencesKey("bind_address")
        val auth = booleanPreferencesKey("auth_enabled")
        val apiKey = stringPreferencesKey("api_key")
        val cors = booleanPreferencesKey("cors_enabled")
        val corsOrigins = stringPreferencesKey("cors_origins")
    }

    suspend fun read(): ApiServerConfig {
        val prefs = context.apiServerDataStore.data.first()
        val apiKey = prefs[Keys.apiKey].orEmpty().ifBlank {
            ApiServerConfig.generateApiKey().also { generated ->
                context.apiServerDataStore.edit { it[Keys.apiKey] = generated }
            }
        }
        return ApiServerConfig(
            enabled = prefs[Keys.enabled] ?: false,
            port = prefs[Keys.port] ?: ApiServerConfig.DEFAULT_PORT,
            bindAddress = prefs[Keys.bind] ?: ApiServerConfig.DEFAULT_BIND,
            authEnabled = prefs[Keys.auth] ?: true,
            apiKey = apiKey,
            corsEnabled = prefs[Keys.cors] ?: false,
            corsOrigins = prefs[Keys.corsOrigins].orEmpty(),
        )
    }

    suspend fun write(config: ApiServerConfig) {
        context.apiServerDataStore.edit {
            it[Keys.enabled] = config.enabled
            it[Keys.port] = config.port
            it[Keys.bind] = config.bindAddress
            it[Keys.auth] = config.authEnabled
            it[Keys.apiKey] = config.apiKey
            it[Keys.cors] = config.corsEnabled
            it[Keys.corsOrigins] = config.corsOrigins
        }
    }
}

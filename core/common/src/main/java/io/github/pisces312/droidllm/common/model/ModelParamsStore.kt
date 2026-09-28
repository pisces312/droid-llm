package io.github.pisces312.droidllm.common.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.engineapi.Backend
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-model sampling override. Every field is nullable: `null` means "inherit
 * the global value from [io.github.pisces312.droidllm.common.settings.AppSettings]".
 *
 * This is an **overlay**, not a copy — a model with no overrides stores nothing,
 * and adding a new global setting needs no migration here. Persisted in its own
 * DataStore (`droid_model_params`) so settings import/export can round-trip it
 * independently of the model registry.
 */
@Serializable
data class ModelParamsOverride(
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val threads: Int? = null,
    val maxNewTokens: Int? = null,
    val backend: String? = null,
    val systemPrompt: String? = null,
) {
    /** No field set → the entry carries no information and can be dropped. */
    val isEmpty: Boolean
        get() = temperature == null && topK == null && topP == null && threads == null &&
            maxNewTokens == null && backend == null && systemPrompt == null

    companion object {
        fun backendOf(raw: String?): Backend? =
            raw?.let { r -> Backend.entries.firstOrNull { it.name == r } }
    }
}

interface ModelParamsStore {
    /** modelId → override, for every model that has at least one non-null field. */
    fun observe(): Flow<Map<String, ModelParamsOverride>>
    suspend fun all(): Map<String, ModelParamsOverride>
    suspend fun get(modelId: String): ModelParamsOverride?
    suspend fun set(modelId: String, override: ModelParamsOverride?)

    /** Bulk replace used by settings import (merge is decided by the caller). */
    suspend fun replaceAll(overrides: Map<String, ModelParamsOverride>)
}

private val Context.modelParamsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "droid_model_params",
)

@Singleton
class DataStoreModelParamsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelParamsStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("model_params_json")

    override fun observe(): Flow<Map<String, ModelParamsOverride>> =
        context.modelParamsDataStore.data.map { prefs -> decode(prefs[key]) }

    override suspend fun all(): Map<String, ModelParamsOverride> {
        val prefs = context.modelParamsDataStore.data.first()
        return decode(prefs[key])
    }

    override suspend fun get(modelId: String): ModelParamsOverride? = all()[modelId]

    override suspend fun set(modelId: String, override: ModelParamsOverride?) {
        context.modelParamsDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableMap()
            if (override == null || override.isEmpty) {
                current.remove(modelId)
            } else {
                current[modelId] = override
            }
            prefs[key] = json.encodeToString(current)
        }
    }

    override suspend fun replaceAll(overrides: Map<String, ModelParamsOverride>) {
        val cleaned = overrides.filterValues { !it.isEmpty }
        context.modelParamsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(cleaned)
        }
    }

    private fun decode(raw: String?): Map<String, ModelParamsOverride> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ModelParamsOverride>>(raw)
        }.getOrDefault(emptyMap())
    }
}

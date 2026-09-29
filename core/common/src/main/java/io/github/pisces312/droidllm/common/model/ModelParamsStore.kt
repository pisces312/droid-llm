package io.github.pisces312.droidllm.common.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.EngineId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-model sampling / execution overlay. Every field is nullable: `null` means
 * "inherit the engine's own defaults" (`EngineDefaults` on the adapter), except
 * `systemPrompt`, which falls back to the app-level setting.
 *
 * This is an **overlay**, not a copy — a model with no overrides stores nothing,
 * and adding a new knob needs no migration here. Persisted in its own DataStore
 * (`droid_model_params`) so settings import/export can round-trip it
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

/**
 * Storage key for one overlay entry: **the engine is part of the identity**, not
 * just the model.
 *
 * The same model family needs different values under different engines —
 * Qualcomm ships `temp 0.8 / top-k 1` for gemma3-1b on QNN/HTP while Google
 * ships `temp 1.0 / top-k 64` for the same model on LiteRT-LM — so keying by
 * model alone would let one engine's pin silently overwrite the other's the
 * moment a file is registered under two engines.
 */
fun modelParamsKey(engineId: EngineId, modelId: String): String = "${engineId.name}:$modelId"

/**
 * Look up the overlay for [engineId]+[modelId], tolerating entries written before
 * the engine became part of the key (v1 stored a bare `modelId`).
 *
 * Legacy entries are read, never rewritten — the next [ModelParamsStore.set] for
 * that pair writes the new key and drops the stale one.
 */
fun Map<String, ModelParamsOverride>.find(
    engineId: EngineId,
    modelId: String,
): ModelParamsOverride? = this[modelParamsKey(engineId, modelId)] ?: this[modelId]

interface ModelParamsStore {
    /** `"ENGINE:modelId"` → override, for every entry with at least one non-null field. */
    fun observe(): Flow<Map<String, ModelParamsOverride>>
    suspend fun all(): Map<String, ModelParamsOverride>
    suspend fun get(engineId: EngineId, modelId: String): ModelParamsOverride?
    suspend fun set(engineId: EngineId, modelId: String, override: ModelParamsOverride?)

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

    override suspend fun get(engineId: EngineId, modelId: String): ModelParamsOverride? =
        all().find(engineId, modelId)

    override suspend fun set(engineId: EngineId, modelId: String, override: ModelParamsOverride?) {
        val scopedKey = modelParamsKey(engineId, modelId)
        context.modelParamsDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableMap()
            // The bare-modelId key is the v1 layout. Once this pair is written under
            // its engine-scoped key the old one is stale: `find` would happily read it
            // for a *different* engine running the same model id.
            current.remove(modelId)
            if (override == null || override.isEmpty) {
                current.remove(scopedKey)
            } else {
                current[scopedKey] = override
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

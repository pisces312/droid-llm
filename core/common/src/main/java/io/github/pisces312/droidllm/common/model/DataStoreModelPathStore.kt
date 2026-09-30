package io.github.pisces312.droidllm.common.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.engineIdFromStorage
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.modelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "droid_models",
)

@Serializable
data class StoredModel(
    val id: String,
    val engineId: String,
    val displayName: String,
    val locationType: String,
    val locationValue: String,
    val formatHint: String? = null,
    val fileSizeBytes: Long? = null,
    val quantHint: String? = null,
) {
    /**
     * Rebuild the runtime model. Returns null when [engineId] is not a known
     * [EngineId] (import from a different release, engine removed) so one bad
     * row cannot take down observeModels()/listModels() (CODE_REVIEW B5).
     */
    fun toLocal(): LocalModel? {
        val engine = engineIdFromStorage(engineId) ?: return null
        return LocalModel(
            id = id,
            engineId = engine,
            displayName = displayName,
            location = when (locationType) {
                "saf" -> ModelLocation.SafUri(locationValue)
                "app_private" -> ModelLocation.AppPrivate(locationValue)
                else -> ModelLocation.FilePath(locationValue)
            },
            formatHint = formatHint,
            fileSizeBytes = fileSizeBytes,
            quantHint = quantHint,
        )
    }

    companion object {
        /** Persist a runtime model. */
        fun from(model: LocalModel): StoredModel {
            val loc = model.location
            return StoredModel(
                id = model.id,
                engineId = model.engineId.name,
                displayName = model.displayName,
                locationType = when (loc) {
                    is ModelLocation.FilePath -> "file"
                    is ModelLocation.SafUri -> "saf"
                    is ModelLocation.AppPrivate -> "app_private"
                },
                locationValue = when (loc) {
                    is ModelLocation.FilePath -> loc.path
                    is ModelLocation.SafUri -> loc.uri
                    is ModelLocation.AppPrivate -> loc.relativePath
                },
                formatHint = model.formatHint,
                fileSizeBytes = model.fileSizeBytes,
                quantHint = model.quantHint,
            )
        }
    }
}

@Singleton
class DataStoreModelPathStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelPathStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("models_json")

    override fun observeModels(): Flow<List<LocalModel>> =
        context.modelDataStore.data.map { prefs -> decode(prefs[key]).mapNotNull { it.toLocalOrWarn() } }

    override suspend fun upsert(model: LocalModel) {
        context.modelDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableList()
            current.removeAll { it.id == model.id }
            current += StoredModel.from(model)
            prefs[key] = json.encodeToString(current)
        }
    }

    override suspend fun delete(modelId: String) {
        context.modelDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableList()
            current.removeAll { it.id == modelId }
            prefs[key] = json.encodeToString(current)
        }
    }

    override suspend fun listModels(): List<LocalModel> {
        val prefs = context.modelDataStore.data.first()
        return decode(prefs[key]).mapNotNull { it.toLocalOrWarn() }
    }

    override suspend fun listModels(engineId: EngineId): List<LocalModel> =
        listModels().filter { it.engineId == engineId }

    override suspend fun applyImported(models: List<StoredModel>) {
        if (models.isEmpty()) return
        context.modelDataStore.edit { prefs ->
            val merged = decode(prefs[key]).toMutableList()
            val incoming = models.associateBy { it.id }
            // Overwrite by id, keep everything else.
            merged.replaceAll { existing -> incoming[existing.id] ?: existing }
            val known = merged.mapTo(HashSet()) { it.id }
            merged += models.filter { it.id !in known }
            prefs[key] = json.encodeToString(merged)
        }
    }

    private fun decode(raw: String?): List<StoredModel> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredModel>>(raw) }.getOrDefault(emptyList())
    }

    private fun StoredModel.toLocalOrWarn(): LocalModel? {
        val local = toLocal()
        if (local == null) {
            Log.w(TAG, "skipping model '$id': unknown engineId='$engineId'")
        }
        return local
    }

    private companion object {
        const val TAG = "ModelPathStore"
    }
}

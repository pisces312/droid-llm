package io.github.pisces312.droidllm.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.InferenceConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
    ;

    companion object {
        fun from(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * Prepended to every request as a `system` message (设置 → 系统提示词).
 *
 * Engines format the conversation with the model's own chat template, and most
 * templates only emit a system block when the **first** message has role
 * `system` — see `docs/mnn.md`. Without it a very short first turn (`hi`) can
 * make a small model emit EOS immediately and produce a blank reply.
 */
const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."

/**
 * User defaults persisted in DataStore. Sampling values seed the Chat
 * sampling panel and can be overridden per session (UI_DESIGN §5.4).
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** false = single-model residency: switch unloads the previous session (DESIGN §3.3). */
    val multiModelResidency: Boolean = false,
    /**
     * Shared model root for every engine. Null = [io.github.pisces312.droidllm.common.device.DeviceProbe.defaultModelRoot].
     * Engines keep their own subfolder names under this root when scanning.
     */
    val modelRootPath: String? = null,
    /** true = the all-files-access guide was already shown (shown on first model-root change). */
    val storageGuideSeen: Boolean = false,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val threads: Int = 4,
    val maxNewTokens: Int = 4096,
    val backend: Backend = Backend.AUTO,
    /** Blank = no system message is sent at all. */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    fun toInferenceConfig(): InferenceConfig = InferenceConfig(
        maxNewTokens = maxNewTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        threads = threads,
        backend = backend,
        systemPrompt = systemPrompt.takeIf { it.isNotBlank() },
    )
}

interface AppSettingsStore {
    fun observe(): Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setMultiModelResidency(enabled: Boolean)
    suspend fun setModelRoot(path: String?)
    suspend fun setStorageGuideSeen(seen: Boolean)
    suspend fun setSystemPrompt(prompt: String)
    suspend fun setSampling(
        temperature: Float,
        topK: Int,
        topP: Float,
        threads: Int,
        maxNewTokens: Int,
        backend: Backend,
    )
}

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "droid_app_settings",
)

@Singleton
class DataStoreAppSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppSettingsStore {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val multiResidency = booleanPreferencesKey("multi_model_residency")
        val modelRoot = stringPreferencesKey("model_root_path")
        val storageGuideSeen = booleanPreferencesKey("storage_guide_seen")
        val temperature = floatPreferencesKey("temperature")
        val topK = intPreferencesKey("top_k")
        val topP = floatPreferencesKey("top_p")
        val threads = intPreferencesKey("threads")
        val maxNewTokens = intPreferencesKey("max_new_tokens")
        val backend = stringPreferencesKey("backend")
        val systemPrompt = stringPreferencesKey("system_prompt")
    }

    override fun observe(): Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[Keys.themeMode]),
            multiModelResidency = prefs[Keys.multiResidency] ?: false,
            modelRootPath = prefs[Keys.modelRoot]?.takeIf { it.isNotBlank() },
            storageGuideSeen = prefs[Keys.storageGuideSeen] ?: false,
            temperature = prefs[Keys.temperature] ?: 0.7f,
            topK = prefs[Keys.topK] ?: 40,
            topP = prefs[Keys.topP] ?: 0.95f,
            threads = prefs[Keys.threads] ?: 4,
            maxNewTokens = prefs[Keys.maxNewTokens] ?: 4096,
            backend = prefs[Keys.backend]?.let { raw ->
                Backend.entries.firstOrNull { it.name == raw }
            } ?: Backend.AUTO,
            systemPrompt = prefs[Keys.systemPrompt] ?: DEFAULT_SYSTEM_PROMPT,
        )
    }

    override suspend fun current(): AppSettings = observe().first()

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.appSettingsDataStore.edit { it[Keys.themeMode] = mode.name }
    }

    override suspend fun setMultiModelResidency(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[Keys.multiResidency] = enabled }
    }

    override suspend fun setModelRoot(path: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(Keys.modelRoot)
            else prefs[Keys.modelRoot] = path
        }
    }

    override suspend fun setStorageGuideSeen(seen: Boolean) {
        context.appSettingsDataStore.edit { it[Keys.storageGuideSeen] = seen }
    }

    override suspend fun setSystemPrompt(prompt: String) {
        context.appSettingsDataStore.edit { it[Keys.systemPrompt] = prompt }
    }

    override suspend fun setSampling(
        temperature: Float,
        topK: Int,
        topP: Float,
        threads: Int,
        maxNewTokens: Int,
        backend: Backend,
    ) {
        context.appSettingsDataStore.edit {
            it[Keys.temperature] = temperature
            it[Keys.topK] = topK
            it[Keys.topP] = topP
            it[Keys.threads] = threads
            it[Keys.maxNewTokens] = maxNewTokens
            it[Keys.backend] = backend.name
        }
    }
}

package io.github.pisces312.droidllm.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * User defaults persisted in DataStore.
 *
 * Deliberately **no sampling values**: temperature / topK / topP / threads /
 * maxNewTokens / backend are a property of the engine (its `EngineDefaults`) and
 * of the individual model (the per-model overlay), never of the app. An
 * app-wide default was inherited by Gemma 3 on LiteRT-LM and turned the greeting
 * into a repetition loop — see `docs/litert.md` §3.3.
 *
 * `systemPrompt` is the one exception and stays here: it is a prompt, not a
 * sampling knob, and an empty one breaks MNN (`docs/mnn.md` §2). It can still be
 * pinned per model in the chat sampling sheet.
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
    /**
     * HuggingFace download host: true = hf-mirror.com (default, reachable in CN),
     * false = huggingface.co. Only the host changes; the catalog repo id and the
     * on-disk `hf/` layout are identical, so switching needs no migration.
     */
    val hfUseMirror: Boolean = true,
    /** Blank = no system message is sent at all. */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * In-app diagnostic log capture (设置 → 诊断). ON by default so a bug can be
     * reproduced without first visiting settings; the buffer is a bounded ring,
     * so leaving it on costs memory only, never growing without limit.
     */
    val diagnosticLogging: Boolean = true,
)

interface AppSettingsStore {
    fun observe(): Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setMultiModelResidency(enabled: Boolean)
    suspend fun setModelRoot(path: String?)
    suspend fun setStorageGuideSeen(seen: Boolean)
    suspend fun setHfUseMirror(useMirror: Boolean)
    suspend fun setSystemPrompt(prompt: String)
    suspend fun setDiagnosticLogging(enabled: Boolean)

    /**
     * Overwrite every user-authored setting at once (settings import).
     *
     * [modelRootPath] and [storageGuideSeen] are deliberately not written —
     * they are device-local, and an import must never move the model root or
     * re-trigger onboarding.
     */
    suspend fun applyImported(imported: AppSettings)
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
        val hfUseMirror = booleanPreferencesKey("hf_use_mirror")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val diagnosticLogging = booleanPreferencesKey("diagnostic_logging")
        // Retired sampling keys (temperature / top_k / top_p / threads /
        // max_new_tokens / backend) are intentionally not declared: sampling now
        // lives on the engine (`EngineDefaults`) and the per-model overlay. Any
        // leftover values in DataStore are ignored, which is the chosen migration
        // (they cannot be attributed to an engine after the fact).
    }

    override fun observe(): Flow<AppSettings> = context.appSettingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[Keys.themeMode]),
            multiModelResidency = prefs[Keys.multiResidency] ?: false,
            modelRootPath = prefs[Keys.modelRoot]?.takeIf { it.isNotBlank() },
            storageGuideSeen = prefs[Keys.storageGuideSeen] ?: false,
            hfUseMirror = prefs[Keys.hfUseMirror] ?: true,
            systemPrompt = prefs[Keys.systemPrompt] ?: DEFAULT_SYSTEM_PROMPT,
            diagnosticLogging = prefs[Keys.diagnosticLogging] ?: true,
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

    override suspend fun setHfUseMirror(useMirror: Boolean) {
        context.appSettingsDataStore.edit { it[Keys.hfUseMirror] = useMirror }
    }

    override suspend fun setSystemPrompt(prompt: String) {
        context.appSettingsDataStore.edit { it[Keys.systemPrompt] = prompt }
    }

    override suspend fun setDiagnosticLogging(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[Keys.diagnosticLogging] = enabled }
    }

    override suspend fun applyImported(imported: AppSettings) {
        context.appSettingsDataStore.edit {
            it[Keys.themeMode] = imported.themeMode.name
            it[Keys.multiResidency] = imported.multiModelResidency
            it[Keys.hfUseMirror] = imported.hfUseMirror
            it[Keys.systemPrompt] = imported.systemPrompt
            // diagnosticLogging is a device-local preference (like modelRootPath):
            // BundledSettings never carries it, so writing `imported.diagnosticLogging`
            // here would force the default (true) over the user's local choice.
        }
    }
}

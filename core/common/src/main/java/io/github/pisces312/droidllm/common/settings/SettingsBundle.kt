package io.github.pisces312.droidllm.common.settings

import io.github.pisces312.droidllm.common.model.ModelParamsOverride
import io.github.pisces312.droidllm.common.model.StoredModel
import io.github.pisces312.droidllm.engineapi.Backend
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Portable snapshot of everything a user configures, for backup / device migration.
 *
 * Scope is deliberately narrow: settings that are **user-authored configuration**.
 * Ephemeral or device-specific state is excluded —
 *  - `storageGuideSeen` (a one-shot onboarding flag, not a preference),
 *  - the model root path (absolute paths from another device are meaningless),
 *  - the API key (a secret; re-generated per device).
 *
 * Model *files* are never bundled — only the registry entries and their
 * per-model sampling overlays, which are what the user would otherwise re-enter
 * by hand.
 */
@Serializable
data class SettingsBundle(
    /** Bundle schema version. Bump on a breaking shape change; import refuses on mismatch. */
    val version: Int = CURRENT_VERSION,
    val appVersion: String = "",
    /** ISO-8601 local timestamp, informational only. */
    val exportedAt: String = "",
    val settings: BundledSettings = BundledSettings(),
    val models: List<StoredModel> = emptyList(),
    /** modelId → per-model sampling overlay. */
    val modelParams: Map<String, ModelParamsOverride> = emptyMap(),
    /** OpenAI-compatible server settings; present only when the user opted in. */
    val apiServer: BundledApiServer? = null,
) {
    /** Human-readable counts for the import confirmation dialog. */
    fun summary(): String = buildString {
        append("${settings.customizedCount()} 项设置")
        append("、${models.size} 个注册模型")
        append("、${modelParams.count { !it.value.isEmpty }} 组单模型参数")
        if (apiServer != null) append("、API 服务器配置")
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MIME = "application/json"
        const val FILE_PREFIX = "droidllm-settings-"

        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

/** [AppSettings] fields worth carrying across devices. */
@Serializable
data class BundledSettings(
    val themeMode: String = ThemeMode.SYSTEM.name,
    val multiModelResidency: Boolean = false,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val threads: Int = 4,
    val maxNewTokens: Int = 4096,
    val backend: String = "AUTO",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val hfUseMirror: Boolean = true,
) {
    /** Fixing the order makes export diffs stable across runs. */
    fun customizedCount(): Int = listOf(
        themeMode != ThemeMode.SYSTEM.name,
        multiModelResidency,
        temperature != 0.7f,
        topK != 40,
        topP != 0.95f,
        threads != 4,
        maxNewTokens != 4096,
        backend != "AUTO",
        systemPrompt != DEFAULT_SYSTEM_PROMPT,
        !hfUseMirror,
    ).count { it }

    fun toAppSettings(): AppSettings = AppSettings(
        themeMode = ThemeMode.from(themeMode),
        multiModelResidency = multiModelResidency,
        temperature = temperature,
        topK = topK,
        topP = topP,
        threads = threads,
        maxNewTokens = maxNewTokens,
        backend = Backend.entries.firstOrNull { it.name == backend } ?: Backend.AUTO,
        systemPrompt = systemPrompt,
        hfUseMirror = hfUseMirror,
    )

    companion object {
        fun from(s: AppSettings): BundledSettings = BundledSettings(
            themeMode = s.themeMode.name,
            multiModelResidency = s.multiModelResidency,
            temperature = s.temperature,
            topK = s.topK,
            topP = s.topP,
            threads = s.threads,
            maxNewTokens = s.maxNewTokens,
            backend = s.backend.name,
            systemPrompt = s.systemPrompt,
            hfUseMirror = s.hfUseMirror,
        )
    }
}

@Serializable
data class BundledApiServer(
    val port: Int = 8080,
    val bindAddress: String = "127.0.0.1",
    val authEnabled: Boolean = true,
    val corsEnabled: Boolean = false,
    val corsOrigins: String = "",
)

/** Outcome of parsing an imported file. */
sealed interface BundleParseResult {
    data class Ok(val bundle: SettingsBundle) : BundleParseResult
    data object Empty : BundleParseResult
    data class Malformed(val detail: String) : BundleParseResult
    data class VersionMismatch(val found: Int, val expected: Int) : BundleParseResult
}

/** Serialize/deserialize [SettingsBundle] with graceful failure reporting. */
object SettingsBundleCodec {

    fun encode(bundle: SettingsBundle): String =
        SettingsBundle.json.encodeToString(SettingsBundle.serializer(), bundle)

    fun decode(text: String): BundleParseResult {
        if (text.isBlank()) return BundleParseResult.Empty
        val bundle = runCatching {
            SettingsBundle.json.decodeFromString(SettingsBundle.serializer(), text)
        }.getOrElse { e ->
            return BundleParseResult.Malformed(e.message ?: e.javaClass.simpleName)
        }
        if (bundle.version > SettingsBundle.CURRENT_VERSION) {
            return BundleParseResult.VersionMismatch(bundle.version, SettingsBundle.CURRENT_VERSION)
        }
        return BundleParseResult.Ok(bundle)
    }
}

/** What an import of [bundle] would change on this device. */
data class ImportPlan(
    val settings: BundledSettings,
    /** Registry entries to add; entries whose id already exists are updated in place. */
    val modelsToAdd: List<StoredModel>,
    val modelsToUpdate: List<StoredModel>,
    /** Overlays to write (already merged: existing entries not present in the file are kept). */
    val modelParamsToWrite: Map<String, ModelParamsOverride>,
    val apiServer: BundledApiServer?,
)

/**
 * Compute the merge without touching any store.
 *
 * Merge policy — **additive, never destructive**:
 *  - settings: the file wins outright (it is an explicit user action);
 *  - models: keyed by id; new ids are appended, existing ids are overwritten;
 *    models present only on this device are left untouched;
 *  - per-model params: same rule, keyed by model id.
 */
object SettingsBundleMerger {

    fun plan(
        bundle: SettingsBundle,
        existingModels: List<StoredModel>,
        existingParams: Map<String, ModelParamsOverride>,
        includeApiServer: Boolean,
    ): ImportPlan {
        val existingById = existingModels.associateBy { it.id }
        val add = bundle.models.filter { it.id !in existingById }
        val update = bundle.models.filter { it.id in existingById }
        return ImportPlan(
            settings = bundle.settings,
            modelsToAdd = add,
            modelsToUpdate = update,
            modelParamsToWrite = existingParams + bundle.modelParams.filterValues { !it.isEmpty },
            apiServer = bundle.apiServer?.takeIf { includeApiServer },
        )
    }
}

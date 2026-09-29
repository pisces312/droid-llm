package io.github.pisces312.droidllm.ui.settings

import androidx.compose.ui.res.stringResource
import io.github.pisces312.droidllm.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.common.bench.BenchmarkDao
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.api.ApiServerPreferences
import io.github.pisces312.droidllm.apiserver.ApiServerConfig
import io.github.pisces312.droidllm.common.model.ModelParamsStore
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.model.ModelRootMigrator
import io.github.pisces312.droidllm.common.model.StoredModel
import io.github.pisces312.droidllm.common.settings.AppLanguage
import io.github.pisces312.droidllm.common.settings.AppSettings
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.BundledApiServer
import io.github.pisces312.droidllm.common.settings.BundledSettings
import io.github.pisces312.droidllm.common.settings.BundleParseResult
import io.github.pisces312.droidllm.common.settings.DEFAULT_SYSTEM_PROMPT
import io.github.pisces312.droidllm.common.settings.ImportPlan
import io.github.pisces312.droidllm.common.settings.SettingsBundle
import io.github.pisces312.droidllm.common.settings.SettingsBundleCodec
import io.github.pisces312.droidllm.common.settings.SettingsBundleMerger
import io.github.pisces312.droidllm.common.settings.ThemeMode
import io.github.pisces312.droidllm.data.catalog.ModelAutoImporter
import io.github.pisces312.droidllm.service.ApiForegroundService
import io.github.pisces312.droidllm.engineapi.EngineDefaults
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.ProbeContext
import io.github.pisces312.droidllm.ui.components.ChoiceChipRow
import io.github.pisces312.droidllm.ui.components.DroidCard
import io.github.pisces312.droidllm.ui.components.NumericField
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.models.FileBrowserDialog
import io.github.pisces312.droidllm.ui.models.FileBrowserRules
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One packaged shared library, as installed on this device.
 *
 * The MD5 is the point: comparing it against the same-named `.so` inside
 * another app answers "do we actually link the same binary?" without
 * guessing at build flags — see `docs/mnn.md`.
 */
data class NativeLibraryInfo(
    val name: String,
    val sizeBytes: Long,
    /** Lowercase hex MD5, or "—" when the file could not be hashed. */
    val md5: String,
)

/**
 * One engine's own sampling defaults, rendered read-only in Settings.
 *
 * @param name the engine's [LlmEngine.displayName].
 */
data class EngineDefaultsRow(
    val name: String,
    val defaults: EngineDefaults,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceProbe: DeviceProbe,
    private val settingsStore: AppSettingsStore,
    private val benchmarkDao: BenchmarkDao,
    private val modelStore: ModelPathStore,
    private val modelParamsStore: ModelParamsStore,
    private val autoImporter: ModelAutoImporter,
    private val apiPrefs: ApiServerPreferences,
    private val engines: Set<@JvmSuppressWildcards LlmEngine>,
) : ViewModel() {

    private val _probe = MutableStateFlow<ProbeContext?>(null)
    val probe: StateFlow<ProbeContext?> = _probe.asStateFlow()

    private val _apiConfig = MutableStateFlow(ApiServerConfig())
    val apiConfig: StateFlow<ApiServerConfig> = _apiConfig.asStateFlow()

    init {
        _probe.value = deviceProbe.probe()
        viewModelScope.launch {
            _apiConfig.value = apiPrefs.current()
        }
    }

    fun setApiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            apiPrefs.setEnabled(enabled)
            _apiConfig.value = apiPrefs.current()
            if (enabled) {
                ApiForegroundService.start(context)
                _message.value = context.getString(R.string.settings_api_starting)
            } else {
                ApiForegroundService.stop(context)
                _message.value = context.getString(R.string.settings_api_stopped)
            }
        }
    }

    fun updateApiConfig(
        port: Int? = null,
        bindAddress: String? = null,
        authEnabled: Boolean? = null,
        apiKey: String? = null,
    ) {
        viewModelScope.launch {
            val cur = apiPrefs.current()
            val next = cur.copy(
                port = port ?: cur.port,
                bindAddress = bindAddress ?: cur.bindAddress,
                authEnabled = authEnabled ?: cur.authEnabled,
                apiKey = apiKey ?: cur.apiKey,
            )
            apiPrefs.update(next)
            _apiConfig.value = apiPrefs.current()
            if (next.enabled) {
                ApiForegroundService.stop(context)
                ApiForegroundService.start(context)
            }
        }
    }

    fun regenerateApiKey() {
        viewModelScope.launch {
            _apiConfig.value = apiPrefs.regenerateApiKey()
            _message.value = context.getString(R.string.settings_api_key_regenerated)
        }
    }

    /** Null until [loadLibraries] runs — hashing several MB is not free. */
    private val _libraries = MutableStateFlow<List<NativeLibraryInfo>?>(null)
    val libraries: StateFlow<List<NativeLibraryInfo>?> = _libraries.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    fun modelRoot(): String =
        settings.value.modelRootPath ?: deviceProbe.defaultModelRoot().absolutePath

    fun defaultModelRootPath(): String =
        deviceProbe.defaultModelRoot().absolutePath

    fun benchmarkDir(): String = deviceProbe.defaultModelRoot().parentFile
        ?.resolve("benchmark")?.absolutePath
        ?: (deviceProbe.defaultModelRoot().absolutePath + "/../benchmark")

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    /**
     * Persist the UI language. `MainActivity` observes the store and pushes the
     * value into AppCompat's app-locale list, which is what switches strings.
     */
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsStore.setLanguage(language) }
    }

    fun setMultiResidency(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMultiModelResidency(enabled) }
    }

    fun setHfUseMirror(useMirror: Boolean) {
        viewModelScope.launch { settingsStore.setHfUseMirror(useMirror) }
    }

    /**
     * The sampling / backend values each engine ships with — **for display only**.
     *
     * They are compiled into the adapters rather than stored here, because the
     * right sampling for a model family is a property of the engine running it:
     * Gemma 3 degenerates into a greeting loop at `0.7 / 40` on LiteRT-LM, while
     * Qualcomm pins `temp 0.8 / top-k 1` for that same model on NPU (see
     * `docs/ENGINE_INTEGRATION.md`). Changing a value happens per model, in the
     * chat sampling sheet.
     */
    val engineDefaults: List<EngineDefaultsRow> = engines
        .map { EngineDefaultsRow(name = it.displayName, defaults = it.defaults) }
        .sortedBy { it.name }

    fun clearBenchDb() {
        viewModelScope.launch {
            benchmarkDao.clear()
            _message.value = context.getString(R.string.settings_bench_cleared)
        }
    }

    fun markStorageGuideSeen() {
        viewModelScope.launch { settingsStore.setStorageGuideSeen(true) }
    }

    fun setSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsStore.setSystemPrompt(prompt) }
    }

    // ---- Settings export / import (SAF) ------------------------------------

    /** Non-null while an import awaits the user's confirmation. */
    private val _pendingImport = MutableStateFlow<ImportPlan?>(null)
    val pendingImport: StateFlow<ImportPlan?> = _pendingImport.asStateFlow()

    /** Set after a successful export so the UI can show the destination URI. */
    private val _lastExport = MutableStateFlow<String?>(null)
    val lastExport: StateFlow<String?> = _lastExport.asStateFlow()

    /**
     * Build the bundle from the live stores and serialize it.
     * Returns the JSON text; the caller writes it to the SAF handle it owns.
     */
    suspend fun buildExportJson(includeApiServer: Boolean): String {
        val s = settingsStore.current()
        val models = modelStore.listModels().map { StoredModel.from(it) }
        val params = modelParamsStore.all()
        val api = if (includeApiServer) {
            val cur = apiPrefs.current()
            BundledApiServer(
                port = cur.port,
                bindAddress = cur.bindAddress,
                authEnabled = cur.authEnabled,
                corsEnabled = cur.corsEnabled,
                corsOrigins = cur.corsOrigins,
            )
        } else {
            null
        }
        return SettingsBundleCodec.encode(
            SettingsBundle(
                version = SettingsBundle.CURRENT_VERSION,
                appVersion = appVersionName(),
                exportedAt = java.time.LocalDateTime.now().toString().substringBefore('.').replace('T', ' '),
                settings = BundledSettings.from(s),
                models = models,
                modelParams = params,
                apiServer = api,
            ),
        )
    }

    fun noteExported(uriDisplay: String) {
        _lastExport.value = uriDisplay
        _message.value = context.getString(R.string.settings_exported, uriDisplay)
    }

    fun noteImportError(detail: String) {
        _message.value = context.getString(R.string.settings_import_failed, detail)
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * Parse the picked file and stage an import plan. Nothing is written until
     * the user confirms in the dialog — a wrong file must not silently rewrite
     * the settings.
     */
    fun stageImport(text: String) {
        when (val parsed = SettingsBundleCodec.decode(text)) {
            is BundleParseResult.Ok -> {
                viewModelScope.launch {
                    val existingModels = runCatching { modelStore.listModels() }
                        .getOrDefault(emptyList())
                        .map { StoredModel.from(it) }
                    val plan = SettingsBundleMerger.plan(
                        bundle = parsed.bundle,
                        existingModels = existingModels,
                        existingParams = modelParamsStore.all(),
                        includeApiServer = parsed.bundle.apiServer != null,
                    )
                    _pendingImport.value = plan
                }
            }
            BundleParseResult.Empty -> _message.value = context.getString(R.string.settings_import_empty)
            is BundleParseResult.Malformed -> _message.value = context.getString(R.string.settings_import_malformed, parsed.detail)
            is BundleParseResult.VersionMismatch ->
                _message.value = context.getString(R.string.settings_import_version, parsed.found, parsed.expected)
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    /** Apply the staged plan. Additive: local-only models and params survive. */
    fun confirmImport() {
        val plan = _pendingImport.value ?: return
        viewModelScope.launch {
            settingsStore.applyImported(plan.settings.toAppSettings())
            modelStore.applyImported(plan.modelsToAdd + plan.modelsToUpdate)
            modelParamsStore.replaceAll(plan.modelParamsToWrite)
            plan.apiServer?.let { a ->
                val cur = apiPrefs.current()
                apiPrefs.update(
                    cur.copy(
                        port = a.port,
                        bindAddress = a.bindAddress,
                        authEnabled = a.authEnabled,
                        corsEnabled = a.corsEnabled,
                        corsOrigins = a.corsOrigins,
                    ),
                )
                _apiConfig.value = apiPrefs.current()
                if (_apiConfig.value.enabled) {
                    ApiForegroundService.stop(context)
                    ApiForegroundService.start(context)
                }
            }
            _pendingImport.value = null
            _message.value = context.getString(
                R.string.settings_imported,
                plan.modelsToAdd.size,
                plan.modelsToUpdate.size,
                plan.modelParamsToWrite.size,
            )
        }
    }

    /**
     * Hash every packaged `.so` once per session so the About panel can be
     * compared against the same-named libraries shipped by other apps.
     */
    fun loadLibraries() {
        if (_libraries.value != null) return
        viewModelScope.launch {
            _libraries.value = withContext(Dispatchers.IO) {
                val dir = File(context.applicationInfo.nativeLibraryDir)
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.sortedBy { it.name }
                    ?.map { file ->
                        NativeLibraryInfo(
                            name = file.name,
                            sizeBytes = file.length(),
                            md5 = md5Of(file),
                        )
                    }
                    .orEmpty()
            }
        }
    }

    private fun md5Of(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("—")

    /**
     * Switch the shared model root to [newRoot].
     * @param migrate true = move old-root data; false = leave source files in place.
     * @param skipConflicts true = leave name-collision items in the source (never overwrite).
     */
    fun changeModelRoot(
        newRoot: String,
        migrate: Boolean,
        skipConflicts: Boolean = true,
    ) {
        val oldPath = modelRoot()
        val source = File(oldPath)
        val target = File(newRoot)
        if (source.absolutePath == target.absolutePath) {
            _message.value = context.getString(R.string.settings_root_unchanged)
            return
        }
        if (ModelRootMigrator.isSameOrNested(source, target)) {
            _message.value = context.getString(R.string.settings_root_nested)
            return
        }
        viewModelScope.launch {
            if (migrate) {
                val result = withContext(Dispatchers.IO) {
                    ModelRootMigrator.migrate(source, target)
                }
                if (result.errors.isNotEmpty()) {
                    _message.value = context.getString(R.string.settings_migrate_failed, result.errors.first())
                    return@launch
                }
                rewriteRegisteredPaths(result.movedPaths)
                val skipped = if (result.skippedNames.isEmpty()) {
                    ""
                } else {
                    context.getString(R.string.settings_skipped_names, result.skippedNames.joinToString("、"))
                }
                settingsStore.setModelRoot(target.absolutePath)
                val added = withContext(Dispatchers.IO) { autoImporter.registerFound(target) }
                _message.value = context.getString(R.string.settings_migrated, result.movedPaths.size) +
                    skipped + addedSuffix(added)
            } else {
                // Do not migrate: just point at the new root. Source data stays.
                if (!target.exists() && !target.mkdirs()) {
                    _message.value = context.getString(R.string.settings_mkdir_failed, target.absolutePath)
                    return@launch
                }
                settingsStore.setModelRoot(target.absolutePath)
                // Files already under the new root must land in the imported list,
                // otherwise the chat model picker stays empty after the switch.
                val added = withContext(Dispatchers.IO) { autoImporter.registerFound(target) }
                _message.value = context.getString(R.string.settings_root_changed) + addedSuffix(added)
            }
        }
    }

    private fun addedSuffix(added: List<String>): String =
        if (added.isNotEmpty()) context.getString(R.string.settings_auto_registered, added.size) else ""

    private suspend fun rewriteRegisteredPaths(moved: Map<String, String>) {
        if (moved.isEmpty()) return
        for (model in modelStore.listModels()) {
            val loc = model.location
            if (loc !is ModelLocation.FilePath) continue
            val newPath = ModelRootMigrator.remapPath(loc.path, moved) ?: continue
            modelStore.upsert(model.copy(location = ModelLocation.FilePath(newPath)))
        }
    }
}

@Composable
fun SettingsScreen(
    onOpenLogs: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val probe by vm.probe.collectAsState()
    val settings by vm.settings.collectAsState()
    val apiConfig by vm.apiConfig.collectAsState()
    val message by vm.message.collectAsState()
    val libraries by vm.libraries.collectAsState()
    val context = LocalContext.current
    var showBrowser by remember { mutableStateOf(false) }
    var pendingRoot by remember { mutableStateOf<String?>(null) }
    var migratePrompt by remember { mutableStateOf(false) }
    var conflictPrompt by remember { mutableStateOf<List<String>>(emptyList()) }
    var storagePrompt by remember { mutableStateOf(false) }
    var metricsHelp by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }
    var exportIncludeApi by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }
    val importPlan by vm.pendingImport.collectAsState()
    val lastExport by vm.lastExport.collectAsState()

    val scope = rememberCoroutineScope()

    // System picker (DocumentsUI): the only route that also exposes the gallery,
    // cloud drives and recents, which a hand-rolled File browser cannot reach.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBundle.MIME),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.buildExportJson(exportIncludeApi)
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error(context.getString(R.string.settings_write_failed))
            }
            if (ok.isSuccess) {
                vm.noteExported(uri.toString())
            } else {
                vm.noteExported(
                    context.getString(
                        R.string.settings_export_failed,
                        ok.exceptionOrNull()?.message
                            ?: context.getString(R.string.common_unknown),
                    )
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: error(context.getString(R.string.settings_read_failed))
            }.getOrElse { e ->
                vm.noteImportError(e.message ?: context.getString(R.string.settings_read_failed))
                return@launch
            }
            vm.stageImport(text)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        SectionCard(stringResource(R.string.settings_section_appearance)) {
            ThemeModeRow(
                selected = settings.themeMode,
                onSelect = vm::setThemeMode,
            )
        }

        SectionCard(stringResource(R.string.settings_language)) {
            AppLanguageRow(
                selected = settings.language,
                onSelect = vm::setLanguage,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.settings_section_engine_defaults)) {
            Text(
                stringResource(R.string.settings_engine_defaults_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            vm.engineDefaults.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                EngineDefaultsRowUi(row)
            }
        }

        SectionCard(stringResource(R.string.settings_section_system_prompt)) {
            Text(
                stringResource(R.string.settings_system_prompt_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = vm::setSystemPrompt,
                label = { Text("system") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedToolButton(
                    stringResource(R.string.action_restore_default),
                    onClick = { vm.setSystemPrompt(DEFAULT_SYSTEM_PROMPT) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedToolButton(
                    stringResource(R.string.settings_system_prompt_clear),
                    onClick = { vm.setSystemPrompt("") },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard(stringResource(R.string.settings_section_memory)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_multi_residency), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_multi_residency_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.multiModelResidency,
                    onCheckedChange = vm::setMultiResidency,
                )
            }
            if (settings.multiModelResidency) {
                Text(
                    stringResource(R.string.settings_multi_residency_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = DroidTheme.extra.warn,
                )
            }
        }

        SectionCard(stringResource(R.string.settings_section_api)) {
            Text(
                stringResource(R.string.settings_api_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_api_enable), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_api_enable_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = apiConfig.enabled,
                    onCheckedChange = vm::setApiEnabled,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericField(
                    label = stringResource(R.string.settings_api_port),
                    value = apiConfig.port.toString(),
                    onCommit = { v ->
                        v.toIntOrNull()?.let { vm.updateApiConfig(port = it) }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = apiConfig.bindAddress,
                    onValueChange = { vm.updateApiConfig(bindAddress = it) },
                    label = { Text(stringResource(R.string.settings_api_bind)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_api_bind_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_api_auth), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_api_auth_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = apiConfig.authEnabled,
                    onCheckedChange = { vm.updateApiConfig(authEnabled = it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("API Key", style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    apiConfig.apiKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                val ctx = LocalContext.current
                OutlinedToolButton(
                    stringResource(R.string.action_copy),
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("api_key", apiConfig.apiKey))
                    },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedToolButton(stringResource(R.string.action_reset), onClick = vm::regenerateApiKey)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.settings_api_example,
                    apiConfig.apiKey,
                    if (apiConfig.bindAddress == "0.0.0.0") {
                        stringResource(R.string.settings_ip_placeholder)
                    } else {
                        "127.0.0.1"
                    },
                    apiConfig.port,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.settings_section_download)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_hf_mirror), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.hfUseMirror) {
                            stringResource(R.string.settings_hf_mirror_on)
                        } else {
                            stringResource(R.string.settings_hf_mirror_off)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.hfUseMirror,
                    onCheckedChange = vm::setHfUseMirror,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_hf_mirror_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.settings_section_data)) {
            val root = vm.modelRoot()
            val def = vm.defaultModelRootPath()
            Text(stringResource(R.string.common_model_root), style = MaterialTheme.typography.labelSmall)
            Text(
                stringResource(R.string.settings_model_root_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(root, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    stringResource(R.string.action_change),
                    onClick = {
                        // Request all-files-access only when the user opts into a custom root.
                        if (!FileBrowserRules.hasAllFilesAccess()) {
                            storagePrompt = true
                        } else {
                            showBrowser = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedToolButton(
                    stringResource(R.string.action_restore_default),
                    onClick = {
                        if (root == def) {
                            // already default
                        } else {
                            pendingRoot = def
                            migratePrompt = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_bench_dir), style = MaterialTheme.typography.labelSmall)
            Text(
                vm.benchmarkDir() + "/bench_*.json",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                stringResource(R.string.settings_clear_bench),
                onClick = vm::clearBenchDb,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_backup), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedToolButton(
                    stringResource(R.string.settings_export),
                    onClick = { exportSheet = true },
                    modifier = Modifier.weight(1f),
                )
                OutlinedToolButton(
                    stringResource(R.string.settings_import),
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f),
                )
            }
            lastExport?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_last_export, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(stringResource(R.string.settings_section_device)) {
            probe?.let { p ->
                Text("SoC: ${p.socModel ?: "unknown"}")
                Text("SDK: ${p.sdkInt}")
                Text("OpenCL: ${p.hasOpenCl}  cdsp: ${p.hasCdspRpc}")
                Text("RAM: ${p.totalRamMb} MB")
                Text(stringResource(R.string.settings_device_storage, p.availableStorageMb))
            }
        }

        SectionCard(stringResource(R.string.settings_section_metrics)) {
            Text(
                stringResource(R.string.settings_metrics_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                stringResource(R.string.settings_metrics_view),
                onClick = { metricsHelp = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(stringResource(R.string.settings_section_diag)) {
            Text(
                stringResource(R.string.settings_diag_desc),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(stringResource(R.string.settings_diag_open), onClick = onOpenLogs, modifier = Modifier.fillMaxWidth())
        }

        SectionCard(stringResource(R.string.settings_section_about)) {
            Text(
                stringResource(R.string.settings_about_desc),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_about_licenses),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                if (aboutExpanded) stringResource(R.string.settings_about_collapse) else stringResource(R.string.settings_about_expand),
                onClick = {
                    aboutExpanded = !aboutExpanded
                    if (aboutExpanded) vm.loadLibraries()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (aboutExpanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_about_libs_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val libs = libraries
                when {
                    libs == null -> Text(stringResource(R.string.settings_about_computing), style = MaterialTheme.typography.labelSmall)
                    libs.isEmpty() -> Text(
                        stringResource(R.string.settings_about_no_libs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    else -> {
                        libs.forEach { lib ->
                            NativeLibraryRow(lib)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        }
                        OutlinedToolButton(
                            stringResource(R.string.settings_about_copy_all),
                            onClick = { copyNativeLibraries(context, libs) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (metricsHelp) {
        AlertDialog(
            onDismissRequest = { metricsHelp = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.settings_metrics_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricDefinition(
                        stringResource(R.string.metric_ttft_term),
                        stringResource(R.string.metric_ttft_desc),
                    )
                    MetricDefinition(
                        stringResource(R.string.metric_prefill_term),
                        stringResource(R.string.metric_prefill_desc),
                    )
                    MetricDefinition(
                        stringResource(R.string.metric_decode_term),
                        stringResource(R.string.metric_decode_desc),
                    )
                    MetricDefinition(
                        stringResource(R.string.metric_p50_term),
                        stringResource(R.string.metric_p50_desc),
                    )
                    MetricDefinition(
                        "prompt → gen tok",
                        stringResource(R.string.metric_tokens_desc),
                    )
                    MetricDefinition(
                        stringResource(R.string.metric_engine_diff_term),
                        stringResource(R.string.metric_engine_diff_desc),
                    )
                    Text(
                        stringResource(R.string.metric_wall_clock_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { metricsHelp = false }) { Text(stringResource(R.string.action_got_it)) }
            },
        )
    }

    if (storagePrompt) {
        AlertDialog(
            onDismissRequest = { storagePrompt = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.settings_storage_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_storage_body),
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    storagePrompt = false
                    vm.markStorageGuideSeen()
                }) { Text(stringResource(R.string.action_later)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    storagePrompt = false
                    vm.markStorageGuideSeen()
                    FileBrowserRules.openAllFilesAccessSettings(context)
                }) { Text(stringResource(R.string.action_grant)) }
            },
        )
    }

    if (showBrowser) {
        val modelRootDir = File(vm.modelRoot())
        FileBrowserDialog(
            engineId = EngineId.FAKE,
            startDir = modelRootDir,
            modelRoot = modelRootDir,
            onPick = { file ->
                showBrowser = false
                if (file.isDirectory) {
                    pendingRoot = file.absolutePath
                    migratePrompt = true
                }
            },
            onDismiss = { showBrowser = false },
        )
    }

    if (exportSheet) {
        AlertDialog(
            onDismissRequest = { exportSheet = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.settings_export_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_export_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_export_include_api), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.settings_export_include_api_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = exportIncludeApi, onCheckedChange = { exportIncludeApi = it })
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { exportSheet = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportSheet = false
                    val stamp = java.time.LocalDate.now().toString().replace("-", "")
                    exportLauncher.launch("${SettingsBundle.FILE_PREFIX}$stamp.json")
                }) { Text(stringResource(R.string.settings_export_choose)) }
            },
        )
    }

    val plan = importPlan
    if (plan != null) {
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.settings_import_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_import_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.settings_import_settings))
                    Text(stringResource(R.string.settings_import_models, plan.modelsToAdd.size, plan.modelsToUpdate.size))
                    Text(stringResource(R.string.settings_import_params, plan.modelParamsToWrite.size))
                    if (plan.apiServer != null) Text(stringResource(R.string.settings_import_api))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_import_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmImport() }) { Text(stringResource(R.string.settings_import_confirm)) }
            },
        )
    }

    val targetRoot = pendingRoot
    if (migratePrompt) {
        val oldRoot = vm.modelRoot()
        val sourceItems = remember(targetRoot, oldRoot) {
            File(oldRoot).listFiles()?.size ?: 0
        }
        AlertDialog(
            onDismissRequest = {
                migratePrompt = false
                pendingRoot = null
            },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.settings_root_change_title)) },
            text = {
                Text(
                    buildString {
                        append(stringResource(R.string.settings_root_change_current))
                        appendLine(oldRoot)
                        append(stringResource(R.string.settings_root_change_target))
                        appendLine(targetRoot ?: "—")
                        if (sourceItems > 0) {
                            appendLine()
                            appendLine(
                                stringResource(R.string.settings_root_change_migrate_prompt, sourceItems)
                            )
                            append(stringResource(R.string.settings_root_change_migrate_note))
                        } else {
                            append(stringResource(R.string.settings_root_change_empty))
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    migratePrompt = false
                    pendingRoot = null
                }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                Row {
                    if (sourceItems > 0) {
                        TextButton(onClick = {
                            migratePrompt = false
                            targetRoot?.let { vm.changeModelRoot(it, migrate = false) }
                            pendingRoot = null
                        }) { Text(stringResource(R.string.settings_root_no_migrate)) }
                    }
                    TextButton(onClick = {
                        migratePrompt = false
                        val target = targetRoot
                        if (target != null) {
                            val plan = ModelRootMigrator.analyze(File(oldRoot), File(target))
                            if (plan.hasConflicts) {
                                conflictPrompt = plan.conflicts.map { it.name }
                            } else {
                                vm.changeModelRoot(target, migrate = true)
                                pendingRoot = null
                            }
                        }
                    }) { Text(stringResource(R.string.settings_root_migrate)) }
                }
            },
        )
    }

    if (conflictPrompt.isNotEmpty() && targetRoot != null) {
        AlertDialog(
            onDismissRequest = {
                conflictPrompt = emptyList()
                pendingRoot = null
            },
            shape = RoundedCornerShape(12.dp),
            title = { Text(stringResource(R.string.settings_conflict_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_conflict_body, conflictPrompt.joinToString("、")),
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    conflictPrompt = emptyList()
                    pendingRoot = null
                }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    conflictPrompt = emptyList()
                    vm.changeModelRoot(targetRoot, migrate = true, skipConflicts = true)
                    pendingRoot = null
                }) { Text(stringResource(R.string.settings_conflict_skip)) }
            },
        )
    }
}

/**
 * One engine's defaults, read-only: engine name first, the six knobs below it.
 * Two lines because six values do not fit one line on a phone.
 */
@Composable
private fun EngineDefaultsRowUi(row: EngineDefaultsRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(row.name, style = MaterialTheme.typography.labelLarge)
        Text(
            "temp ${row.defaults.temperature} · top_k ${row.defaults.topK} · " +
                "top_p ${row.defaults.topP}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "threads ${row.defaults.threads} · max_new ${row.defaults.maxNewTokens} · " +
                row.defaults.backend.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    DroidCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun AppLanguageRow(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    ChoiceChipRow(
        options = AppLanguage.entries,
        selected = selected,
        label = {
            when (it) {
                AppLanguage.SYSTEM -> stringResource(R.string.common_follow_system)
                AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
                AppLanguage.EN -> stringResource(R.string.settings_language_en)
            }
        },
        onSelected = onSelect,
    )
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    // Selection is a chip, not a filled button: a filled button means "do this",
    // so using one to show which theme is current made the other two look disabled.
    ChoiceChipRow(
        options = ThemeMode.entries,
        selected = selected,
        label = {
            when (it) {
                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                ThemeMode.SYSTEM -> stringResource(R.string.common_follow_system)
            }
        },
        onSelected = onSelect,
    )
}

@Composable
private fun NativeLibraryRow(lib: NativeLibraryInfo) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lib.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatBytes(lib.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            lib.md5,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricDefinition(term: String, body: String) {
    Column {
        Text(term, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun copyNativeLibraries(context: Context, libs: List<NativeLibraryInfo>) {
    val text = buildString {
        appendLine("droid-llm native libraries")
        libs.forEach { appendLine("${it.name}\t${it.sizeBytes}\t${it.md5}") }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("droid-llm native libraries", text))
}

package io.github.pisces312.droidllm.ui.settings

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
                _message.value = "API 服务器启动中…"
            } else {
                ApiForegroundService.stop(context)
                _message.value = "API 服务器已停止"
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
            _message.value = "已重新生成 API Key"
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
            _message.value = "已清空 benchmark 库"
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
        _message.value = "已导出设置：$uriDisplay"
    }

    fun noteImportError(detail: String) {
        _message.value = "导入失败：$detail"
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
            BundleParseResult.Empty -> _message.value = "导入失败：文件为空"
            is BundleParseResult.Malformed -> _message.value = "导入失败：不是有效的设置文件（${parsed.detail}）"
            is BundleParseResult.VersionMismatch ->
                _message.value = "导入失败：文件版本 ${parsed.found} 高于本应用支持的 ${parsed.expected}"
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
            _message.value = "已导入：${plan.modelsToAdd.size} 新增 / ${plan.modelsToUpdate.size} 覆盖模型，" +
                "${plan.modelParamsToWrite.size} 组参数"
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
            _message.value = "路径未变化"
            return
        }
        if (ModelRootMigrator.isSameOrNested(source, target)) {
            _message.value = "新旧路径不能互相包含"
            return
        }
        viewModelScope.launch {
            if (migrate) {
                val result = withContext(Dispatchers.IO) {
                    ModelRootMigrator.migrate(source, target)
                }
                if (result.errors.isNotEmpty()) {
                    _message.value = "迁移失败：" + result.errors.first()
                    return@launch
                }
                rewriteRegisteredPaths(result.movedPaths)
                val skipped = if (result.skippedNames.isEmpty()) ""
                else "；跳过重名：" + result.skippedNames.joinToString("、")
                settingsStore.setModelRoot(target.absolutePath)
                val added = withContext(Dispatchers.IO) { autoImporter.registerFound(target) }
                _message.value = "已迁移 ${result.movedPaths.size} 项$skipped" + addedSuffix(added)
            } else {
                // Do not migrate: just point at the new root. Source data stays.
                if (!target.exists() && !target.mkdirs()) {
                    _message.value = "无法创建目标目录：${target.absolutePath}"
                    return@launch
                }
                settingsStore.setModelRoot(target.absolutePath)
                // Files already under the new root must land in the imported list,
                // otherwise the chat model picker stays empty after the switch.
                val added = withContext(Dispatchers.IO) { autoImporter.registerFound(target) }
                _message.value = "已改模型根目录（未迁移原数据）" + addedSuffix(added)
            }
        }
    }

    private fun addedSuffix(added: List<String>): String =
        if (added.isNotEmpty()) "；已自动注册 ${added.size} 个已下载模型" else ""

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
                } ?: error("无法写入所选位置")
            }
            if (ok.isSuccess) {
                vm.noteExported(uri.toString())
            } else {
                vm.noteExported("失败：" + (ok.exceptionOrNull()?.message ?: "未知"))
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
                } ?: error("无法读取所选文件")
            }.getOrElse { e ->
                vm.noteImportError(e.message ?: "无法读取所选文件")
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
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        SectionCard("外观") {
            ThemeModeRow(
                selected = settings.themeMode,
                onSelect = vm::setThemeMode,
            )
        }

        SectionCard("引擎默认采样") {
            Text(
                "采样参数跟着引擎走：每个引擎在自己代码里声明一套默认值，单个模型可以在聊天页" +
                    "按「引擎 + 模型」钉住自己的值（采样面板里标「仅本模型」）。此处只读 —— " +
                    "没有全局采样设置，要调请去聊天页。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            vm.engineDefaults.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                EngineDefaultsRowUi(row)
            }
        }

        SectionCard("系统提示词") {
            Text(
                "作为首条 system 消息随每次请求发给引擎。多数模型的 chat template 只在首条消息为 " +
                    "system 时才渲染 system 段；在 MNN 上留空会让极短的首轮问话（如 hi）直接命中 " +
                    "EOS，出现空回复。详见 docs/mnn.md。",
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
                    "恢复默认",
                    onClick = { vm.setSystemPrompt(DEFAULT_SYSTEM_PROMPT) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedToolButton(
                    "清空（不注入）",
                    onClick = { vm.setSystemPrompt("") },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard("内存") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("多模型驻留", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "开启后切换引擎/模型不自动 unload；默认关闭（单模型驻留）",
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
                    "8GB 机型易 OOM，建议保持关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = DroidTheme.extra.warn,
                )
            }
        }

        SectionCard("API 服务器") {
            Text(
                "内嵌 OpenAI 兼容接口，供 PC / Cherry Studio 等客户端调用本机模型。" +
                    "首期与聊天页互斥使用会话，避免并发生成。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用 API 服务器", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "前台服务常驻，通知栏可停止",
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
                    label = "端口",
                    value = apiConfig.port.toString(),
                    onCommit = { v ->
                        v.toIntOrNull()?.let { vm.updateApiConfig(port = it) }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = apiConfig.bindAddress,
                    onValueChange = { vm.updateApiConfig(bindAddress = it) },
                    label = { Text("绑定 IP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "默认 127.0.0.1 仅本机可访问（需 adb forward）；局域网访问改为 0.0.0.0。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("鉴权", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Bearer / x-api-key 校验 API Key",
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
                    "复制",
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("api_key", apiConfig.apiKey))
                    },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedToolButton("重置", onClick = vm::regenerateApiKey)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "示例：curl -H \"Authorization: Bearer ${apiConfig.apiKey}\" " +
                    "http://${if (apiConfig.bindAddress == "0.0.0.0") "<手机IP>" else "127.0.0.1"}:${apiConfig.port}/v1/models",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("模型下载") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("HF 使用镜像站", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.hfUseMirror) {
                            "当前：hf-mirror.com（国内可直连，默认）"
                        } else {
                            "当前：huggingface.co（官方）"
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
                "只切换下载域名；HF 官方与镜像共用同一份模型仓库 id 和 `hf/` 磁盘布局，" +
                    "来回切换无需迁移或重新校验已下载的模型。ModelScope 固定 modelscope.cn。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("数据") {
            val root = vm.modelRoot()
            val def = vm.defaultModelRootPath()
            Text("模型根目录", style = MaterialTheme.typography.labelSmall)
            Text(
                "所有引擎共享同一根目录（其下按引擎分子目录）。改路径时不删目标文件，重名跳过，可不迁移原数据。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(root, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "修改",
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
                    "恢复默认",
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
            Text("评测导出目录", style = MaterialTheme.typography.labelSmall)
            Text(
                vm.benchmarkDir() + "/bench_*.json",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                "清空 benchmark 库",
                onClick = vm::clearBenchDb,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("设置备份", style = MaterialTheme.typography.titleMedium)
            Text(
                "导出设置参数、已注册模型和每个模型的参数覆盖为一份 JSON，" +
                    "换机或重装后导入即可恢复。不含模型文件本身、模型根目录和 API Key。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedToolButton(
                    "导出…",
                    onClick = { exportSheet = true },
                    modifier = Modifier.weight(1f),
                )
                OutlinedToolButton(
                    "导入…",
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f),
                )
            }
            lastExport?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "上次导出：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("设备信息") {
            probe?.let { p ->
                Text("SoC: ${p.socModel ?: "unknown"}")
                Text("SDK: ${p.sdkInt}")
                Text("OpenCL: ${p.hasOpenCl}  cdsp: ${p.hasCdspRpc}")
                Text("RAM: ${p.totalRamMb} MB")
                Text("可用存储: ${p.availableStorageMb} MB")
            }
        }

        SectionCard("指标说明") {
            Text(
                "聊天页每个回复下方展示的都是引擎回报的性能指标。这里说明它们的口径与定义。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                "查看指标定义",
                onClick = { metricsHelp = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("诊断") {
            Text(
                "引擎加载 / 生成的关键节点、提示词长度与首 token 延迟都会写进运行日志，" +
                    "用于在真机上复现问题后把日志导出给开发者。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton("查看运行日志", onClick = onOpenLogs, modifier = Modifier.fillMaxWidth())
        }

        SectionCard("关于") {
            Text(
                "droid-llm：同一台真机上四引擎（LiteRT-LM / MNN / Genie / llama.cpp）实测对比。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "依赖与许可：LiteRT-LM (Apache-2.0)、MNN (Apache-2.0)、QAIRT/Genie (Qualcomm)、" +
                    "llama.cpp (MIT)、Jetpack Compose / Hilt / Room / DataStore (Apache-2.0)。" +
                    "详见 docs/ENGINE_INTEGRATION.md。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                if (aboutExpanded) "收起原生库信息" else "展开原生库信息（含 MD5）",
                onClick = {
                    aboutExpanded = !aboutExpanded
                    if (aboutExpanded) vm.loadLibraries()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (aboutExpanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "本 APK 打包的原生库（名称 / 大小 / MD5）。拿它和别的 app 里同名 .so 比对，" +
                        "可以直接判断是否链接了同一个二进制，不必再猜编译参数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val libs = libraries
                when {
                    libs == null -> Text("计算中…", style = MaterialTheme.typography.labelSmall)
                    libs.isEmpty() -> Text(
                        "未找到 .so（可能使用压缩打包）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    else -> {
                        libs.forEach { lib ->
                            NativeLibraryRow(lib)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        }
                        OutlinedToolButton(
                            "复制全部",
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
            title = { Text("指标定义") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricDefinition(
                        "TTFT（首 token 延迟）",
                        "从 generate() 请求发出到首个 token 回调。不含模型加载与模板格式化。" +
                            "引擎自报 prefill 耗时时优先采用（如 MNN 的 prefill_us），否则退回墙钟计时。",
                    )
                    MetricDefinition(
                        "prefill（输入处理速率）",
                        "promptTokens ÷ prefill 耗时。衡量模型读完输入的速度；prompt 越长，这项越重要。",
                    )
                    MetricDefinition(
                        "decode（生成速率）",
                        "(generatedTokens − 1) ÷ decode 耗时。逐 token 生成的速度，端侧对比最常用的一项。",
                    )
                    MetricDefinition(
                        "p50（单 token 中位耗时）",
                        "相邻 token 回调间隔的中位数，反映生成过程的稳定度，越小越稳。",
                    )
                    MetricDefinition(
                        "prompt → gen tok",
                        "本轮输入的 token 数与生成的 token 数。",
                    )
                    MetricDefinition(
                        "引擎口径差异",
                        "· MNN：直接读引擎 prefill_us / decode_us，是纯计算耗时，不含 JNI 回调与界面派发，" +
                            "可与 MnnLlmChat 日志里的 PERF 行对比。\n" +
                            "· Genie：流式回调为文本片段而非离散 token，generatedTokens 为回调次数近似值。\n" +
                            "· LiteRT-LM / llama.cpp：引擎不回报分段耗时，使用墙钟口径，" +
                            "因此数值天然低于纯计算速率。",
                    )
                    Text(
                        "同一模型下墙钟口径会低于引擎口径，因为每 token 的 JNI 回调与界面派发都计入前者。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { metricsHelp = false }) { Text("知道了") }
            },
        )
    }

    if (storagePrompt) {
        AlertDialog(
            onDismissRequest = { storagePrompt = false },
            shape = RoundedCornerShape(12.dp),
            title = { Text("需要「所有文件访问」权限") },
            text = {
                Text(
                    "改到自定义模型根目录需要浏览外部存储。\n" +
                        "系统不提供普通弹窗，请在设置里打开「所有文件访问」。\n" +
                        "未授权时仅能使用 App 私有目录作为模型根目录。",
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    storagePrompt = false
                    vm.markStorageGuideSeen()
                }) { Text("稍后再说") }
            },
            confirmButton = {
                TextButton(onClick = {
                    storagePrompt = false
                    vm.markStorageGuideSeen()
                    FileBrowserRules.openAllFilesAccessSettings(context)
                }) { Text("去授权") }
            },
        )
    }

    if (showBrowser) {
        FileBrowserDialog(
            engineId = EngineId.FAKE,
            startDir = File(vm.modelRoot()),
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
            title = { Text("导出设置") },
            text = {
                Column {
                    Text(
                        "将写入一份 JSON：设置参数、已注册模型、单模型参数覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("包含 API 服务器配置", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "端口 / 绑定地址 / 鉴权开关；API Key 永不导出",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = exportIncludeApi, onCheckedChange = { exportIncludeApi = it })
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { exportSheet = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportSheet = false
                    val stamp = java.time.LocalDate.now().toString().replace("-", "")
                    exportLauncher.launch("${SettingsBundle.FILE_PREFIX}$stamp.json")
                }) { Text("选择保存位置") }
            },
        )
    }

    val plan = importPlan
    if (plan != null) {
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            shape = RoundedCornerShape(12.dp),
            title = { Text("导入设置") },
            text = {
                Column {
                    Text("将应用以下内容：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("· 设置参数：覆盖当前值")
                    Text("· 注册模型：新增 ${plan.modelsToAdd.size} 个，覆盖 ${plan.modelsToUpdate.size} 个")
                    Text("· 单模型参数：写入 ${plan.modelParamsToWrite.size} 组")
                    if (plan.apiServer != null) Text("· API 服务器配置：覆盖端口与绑定")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本机独有、文件中没有的模型与参数会保留；模型文件本身不会被改动，" +
                            "若文件不在本机会显示为路径无效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelImport) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmImport() }) { Text("导入") }
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
            title = { Text("修改模型根目录") },
            text = {
                Text(
                    buildString {
                        append("当前：")
                        appendLine(oldRoot)
                        append("目标：")
                        appendLine(targetRoot ?: "—")
                        if (sourceItems > 0) {
                            appendLine()
                            appendLine("原目录有 $sourceItems 项数据，是否一并迁移到新路径？")
                            append("迁移只移动，不删除目标已有文件；重名会再询问。")
                        } else {
                            append("原目录为空，将直接切换。")
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    migratePrompt = false
                    pendingRoot = null
                }) { Text("取消") }
            },
            confirmButton = {
                Row {
                    if (sourceItems > 0) {
                        TextButton(onClick = {
                            migratePrompt = false
                            targetRoot?.let { vm.changeModelRoot(it, migrate = false) }
                            pendingRoot = null
                        }) { Text("不迁移") }
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
                    }) { Text("迁移") }
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
            title = { Text("目标路径存在重名") },
            text = {
                Text(
                    "以下名称在目标路径已存在：\n" +
                        conflictPrompt.joinToString("、") +
                        "\n\n不会删除或覆盖目标文件。继续迁移将跳过这些重名项（原目录保留）。",
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    conflictPrompt = emptyList()
                    pendingRoot = null
                }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    conflictPrompt = emptyList()
                    vm.changeModelRoot(targetRoot, migrate = true, skipConflicts = true)
                    pendingRoot = null
                }) { Text("跳过重名并迁移") }
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
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    // Selection is a chip, not a filled button: a filled button means "do this",
    // so using one to show which theme is current made the other two look disabled.
    ChoiceChipRow(
        options = ThemeMode.entries,
        selected = selected,
        label = {
            when (it) {
                ThemeMode.DARK -> "深色"
                ThemeMode.LIGHT -> "浅色"
                ThemeMode.SYSTEM -> "跟随系统"
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

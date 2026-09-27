package io.github.pisces312.droidllm.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.model.ModelRootMigrator
import io.github.pisces312.droidllm.common.settings.AppSettings
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.DEFAULT_SYSTEM_PROMPT
import io.github.pisces312.droidllm.common.settings.ThemeMode
import io.github.pisces312.droidllm.data.catalog.ModelAutoImporter
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.ProbeContext
import io.github.pisces312.droidllm.ui.components.ChoiceChipRow
import io.github.pisces312.droidllm.ui.components.DroidCard
import io.github.pisces312.droidllm.ui.components.LabeledDropdown
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceProbe: DeviceProbe,
    private val settingsStore: AppSettingsStore,
    private val benchmarkDao: BenchmarkDao,
    private val modelStore: ModelPathStore,
    private val autoImporter: ModelAutoImporter,
) : ViewModel() {

    private val _probe = MutableStateFlow<ProbeContext?>(null)
    val probe: StateFlow<ProbeContext?> = _probe.asStateFlow()

    /** Null until [loadLibraries] runs — hashing several MB is not free. */
    private val _libraries = MutableStateFlow<List<NativeLibraryInfo>?>(null)
    val libraries: StateFlow<List<NativeLibraryInfo>?> = _libraries.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    init {
        _probe.value = deviceProbe.probe()
    }

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

    fun setSampling(
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        threads: Int?,
        maxNewTokens: Int?,
        backend: Backend?,
    ) {
        val cur = settings.value
        viewModelScope.launch {
            settingsStore.setSampling(
                temperature = temperature ?: cur.temperature,
                topK = topK ?: cur.topK,
                topP = topP ?: cur.topP,
                threads = threads ?: cur.threads,
                maxNewTokens = maxNewTokens ?: cur.maxNewTokens,
                backend = backend ?: cur.backend,
            )
        }
    }

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

    private fun addedSuffix(added: Int): String =
        if (added > 0) "；已自动登记 $added 个已下载模型" else ""

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
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val probe by vm.probe.collectAsState()
    val settings by vm.settings.collectAsState()
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

        SectionCard("默认采样参数") {
            Text(
                "聊天页采样面板的默认值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericField(
                    label = "temp",
                    value = settings.temperature.toString(),
                    decimal = true,
                    onCommit = { v ->
                        v.toFloatOrNull()?.let {
                            vm.setSampling(temperature = it, topK = null, topP = null, threads = null, maxNewTokens = null, backend = null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                NumericField(
                    label = "top_k",
                    value = settings.topK.toString(),
                    onCommit = { v ->
                        v.toIntOrNull()?.let {
                            vm.setSampling(temperature = null, topK = it, topP = null, threads = null, maxNewTokens = null, backend = null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericField(
                    label = "top_p",
                    value = settings.topP.toString(),
                    decimal = true,
                    onCommit = { v ->
                        v.toFloatOrNull()?.let {
                            vm.setSampling(temperature = null, topK = null, topP = it, threads = null, maxNewTokens = null, backend = null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                NumericField(
                    label = "threads",
                    value = settings.threads.toString(),
                    onCommit = { v ->
                        v.toIntOrNull()?.let {
                            vm.setSampling(temperature = null, topK = null, topP = null, threads = it, maxNewTokens = null, backend = null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericField(
                    label = "maxNewTokens",
                    value = settings.maxNewTokens.toString(),
                    onCommit = { v ->
                        v.toIntOrNull()?.let {
                            vm.setSampling(temperature = null, topK = null, topP = null, threads = null, maxNewTokens = it, backend = null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                LabeledDropdown(
                    label = "backend",
                    options = Backend.entries.map { it.name to it.name },
                    selectedKey = settings.backend.name,
                    onSelected = { key ->
                        vm.setSampling(temperature = null, topK = null, topP = null, threads = null, maxNewTokens = null, backend = Backend.valueOf(key))
                    },
                    emptyText = "—",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "backend 是默认值，AUTO 由引擎自行决定；某个引擎实际支持哪几项、哪项不生效，" +
                    "见聊天页参数 chip 打开的「采样参数」里的灰显说明。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

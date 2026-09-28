package io.github.pisces312.droidllm.ui.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pisces312.droidllm.data.catalog.DownloadStatus
import io.github.pisces312.droidllm.data.catalog.ModelSource
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.engineapi.engineIdFromStorage
import io.github.pisces312.droidllm.ui.components.ChoiceChipRow
import io.github.pisces312.droidllm.ui.components.DroidCard
import io.github.pisces312.droidllm.ui.components.EmptyState
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.components.SheetTitle
import io.github.pisces312.droidllm.ui.components.VendorLogo
import io.github.pisces312.droidllm.ui.components.formatModelSize

/**
 * Copies [text] to the clipboard and confirms with a toast.
 *
 * Paths here run past 80 characters and exist to be pasted into a file manager or
 * `adb`, so every path the page shows carries its own copy affordance.
 */
@Composable
private fun CopyPathButton(text: String) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("path", text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "复制路径",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun ModelsScreen(vm: ModelsViewModel = hiltViewModel()) {
    val models by vm.models.collectAsState()
    val message by vm.message.collectAsState()
    val pendingPath by vm.pendingPath.collectAsState()
    val modelRoot by vm.root.collectAsState()
    val scanChanges by vm.scanChanges.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    var displayName by remember { mutableStateOf("") }
    var engineId by remember { mutableStateOf(EngineId.LLAMACPP) }
    var showBrowser by remember { mutableStateOf(false) }
    var showRegisterSheet by remember { mutableStateOf(false) }
    var engineFilter by remember { mutableStateOf<EngineId?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "模型管理",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showRegisterSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "注册外部模型")
            }
            IconButton(
                onClick = {
                    if (tab == 0) vm.syncFoundModels() else vm.refreshDownloaded()
                },
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = if (tab == 0) "扫描模型根目录" else "刷新下载状态",
                )
            }
        }
        val rootPath = modelRoot.ifEmpty { vm.modelRoot() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共享模型根目录：$rootPath",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CopyPathButton(rootPath)
        }
        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("已注册") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("模型市场") })
        }
        Spacer(Modifier.height(8.dp))

        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.labelMedium,
                color = if (message.startsWith("校验失败") || message.startsWith("格式") ||
                    message.startsWith("路径") || message.startsWith("显示名") ||
                    message.startsWith("下载失败")
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (tab == 0) {
            // First open of「已注册」auto-scans the model root for newly dropped
            // files so the list is current without a manual tap. Quiet: only
            // additions are announced.
            LaunchedEffect(Unit) { vm.syncFoundModels(quiet = true) }
            LocalModelsTab(
                models = models,
                engineFilter = engineFilter,
                onEngineFilter = { engineFilter = it },
                onGoToMarket = { tab = 1 },
                onValidate = vm::validate,
                onDelete = vm::delete,
            )
        } else {
            MarketTab(vm = vm)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showRegisterSheet) {
        RegisterModelSheet(
            engineId = engineId,
            displayName = displayName,
            pendingPath = pendingPath,
            formatHint = vm.engineFormatHint(engineId),
            modelRoot = modelRoot.ifEmpty { vm.modelRoot() },
            onEngineId = { engineId = it },
            onDisplayName = { displayName = it },
            onPendingPath = { vm.setPendingPath(it) },
            onBrowse = { showBrowser = true },
            onAdd = {
                val started = vm.register(engineId, displayName.trim(), pendingPath.trim())
                if (started) {
                    displayName = ""
                    engineFilter = null
                    showRegisterSheet = false
                }
            },
            onDismiss = { showRegisterSheet = false },
        )
    }

    if (showBrowser) {
        FileBrowserDialog(
            engineId = engineId,
            startDir = runCatching {
                java.io.File(vm.engineDir(engineId))
            }.getOrNull()?.takeIf { it.isDirectory },
            onPick = { file ->
                vm.setPendingPath(file.absolutePath)
                if (displayName.isBlank()) displayName = file.nameWithoutExtension
                showBrowser = false
            },
            onDismiss = { showBrowser = false },
        )
    }

    scanChanges?.let { changes ->
        ScanChangesDialog(
            changes = changes,
            onDismiss = vm::dismissScanChanges,
        )
    }
}

/**
 * Post-scan summary. Raised only when the scan added or removed registrations —
 * a quiet first-entry scan with no changes stays silent.
 */
@Composable
private fun ScanChangesDialog(
    changes: ScanChanges,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text("模型列表已更新") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (changes.added.isNotEmpty()) {
                    Text(
                        "新注册 ${changes.added.size} 个：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    changes.added.forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (changes.removed.isNotEmpty()) {
                    Text(
                        "移除失效 ${changes.removed.size} 个：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    changes.removed.forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "「移除失效」= 原路径下文件已不存在，仅从列表注销，不删磁盘文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/**
 * Pure registration list. Engine filter chips narrow the cards; register and scan
 * live in the page header so this tab stays a list, not a form.
 *
 * [engineFilter] `null` means "all engines".
 */
@Composable
private fun LocalModelsTab(
    models: List<LocalModel>,
    engineFilter: EngineId?,
    onEngineFilter: (EngineId?) -> Unit,
    onGoToMarket: () -> Unit,
    onValidate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val engineOptions = remember {
        listOf<EngineId?>(null) + EngineId.entries.filter { it != EngineId.FAKE }
    }
    val filtered = remember(models, engineFilter) {
        if (engineFilter == null) models else models.filter { it.engineId == engineFilter }
    }

    Column(Modifier.fillMaxSize()) {
        ChoiceChipRow(
            options = engineOptions,
            selected = engineFilter,
            label = { id ->
                val count = if (id == null) models.size else models.count { it.engineId == id }
                if (id == null) "全部 · $count" else "${id.displayName} · $count"
            },
            onSelected = onEngineFilter,
        )
        Spacer(Modifier.height(8.dp))

        if (models.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Folder,
                text = "还没有模型。可从模型市场下载，或点右上角 ＋ 注册本地文件（保留原路径）。",
                actionLabel = "浏览模型市场",
                onAction = onGoToMarket,
            )
            return@Column
        }
        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Folder,
                text = "「${engineFilter?.displayName ?: ""}」下暂无已注册模型。",
                actionLabel = "显示全部",
                onAction = { onEngineFilter(null) },
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered) { model ->
                DroidCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${model.engineId.displayName} · ${model.formatHint ?: "-"}" +
                                (model.quantHint?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val path = when (val loc = model.location) {
                            is ModelLocation.FilePath -> loc.path
                            is ModelLocation.SafUri -> loc.uri
                            is ModelLocation.AppPrivate -> loc.relativePath
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            CopyPathButton(path)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedToolButton(
                                "校验",
                                onClick = { onValidate(model.id) },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedToolButton(
                                "删除",
                                onClick = { onDelete(model.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * External-model registration form. A bottom sheet, not a card on the list tab:
 * registration is occasional, the list is the daily surface (UI_DESIGN §5.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterModelSheet(
    engineId: EngineId,
    displayName: String,
    pendingPath: String,
    formatHint: String,
    modelRoot: String,
    onEngineId: (EngineId) -> Unit,
    onDisplayName: (String) -> Unit,
    onPendingPath: (String) -> Unit,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SheetTitle(
                text = "注册外部模型",
                subtitle = "只登记、不搬文件；市场下载的模型才放在模型根目录下。",
            )
            Spacer(Modifier.height(12.dp))
            ChoiceChipRow(
                options = EngineId.entries.filter { it != EngineId.FAKE },
                selected = engineId,
                label = { it.displayName },
                onSelected = onEngineId,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "格式：$formatHint",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayName,
                label = { Text("显示名") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pendingPath,
                onValueChange = onPendingPath,
                label = { Text("模型文件或目录绝对路径") },
                trailingIcon = {
                    IconButton(onClick = onBrowse) {
                        Icon(Icons.Filled.Folder, contentDescription = "浏览…")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "注册模型（保留原路径）",
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "市场下载（HF/魔塔）统一放在「$modelRoot」下 `{hf|modelscope}/models--org--repo/snapshots/`。" +
                    "四种引擎格式互不通用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MarketTab(vm: ModelsViewModel) {
    val source by vm.source.collectAsState()
    val downloaded by vm.downloadedIds.collectAsState()
    val downloadStates by vm.downloadStates.collectAsState()
    val downloadFilter by vm.downloadFilter.collectAsState()
    val engineFilter by vm.marketEngineFilter.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val hfHost by vm.hfHost.collectAsState()
    val rows = remember(source, downloaded, downloadStates, downloadFilter, engineFilter, catalog) {
        vm.catalogRows(downloadFilter, engineFilter)
    }

    val engineOptions = remember(catalog) {
        listOf<EngineId?>(null) +
            EngineId.entries.filter { id ->
                id != EngineId.FAKE && catalog.models.any { engineIdFromStorage(it.engine) == id }
            }
    }

    Column {
        ChoiceChipRow(
            options = ModelSource.entries,
            selected = source,
            label = { it.displayName },
            onSelected = { vm.setSource(it) },
        )
        Spacer(Modifier.height(6.dp))
        ChoiceChipRow(
            options = engineOptions,
            selected = engineFilter,
            label = { id ->
                if (id == null) {
                    "全部引擎"
                } else {
                    val count = catalog.models.count { engineIdFromStorage(it.engine) == id }
                    "${id.displayName} · $count"
                }
            },
            onSelected = { vm.setMarketEngineFilter(it) },
        )
        Spacer(Modifier.height(6.dp))
        ChoiceChipRow(
            options = DownloadFilter.entries,
            selected = downloadFilter,
            label = { f ->
                when (f) {
                    DownloadFilter.ALL -> "全部"
                    DownloadFilter.DOWNLOADED -> "已下载"
                    DownloadFilter.NOT_DOWNLOADED -> "未下载"
                }
            },
            onSelected = { vm.setDownloadFilter(it) },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "HF 下载地址取自设置（当前 $hfHost，默认镜像 hf-mirror.com），ModelScope = modelscope.cn。" +
                "下载统一存 `{hf|modelscope}/models--org--repo/snapshots/`（MnnLlmChat 同款）。" +
                "刷新下载状态见右上角。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.model.id }) { row ->
                // catalog stores the raw EngineId.name; resolve it for display.
                val engineLabel = engineIdFromStorage(row.model.engine)?.displayName ?: row.model.engine
                DroidCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            VendorLogo(row.model.vendor)
                            Column(Modifier.weight(1f)) {
                                Text(row.model.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "$engineLabel · ${row.model.vendor}" +
                                        (if (row.model.sizeBytes > 0) {
                                            " · " + formatModelSize(row.model.sizeBytes)
                                        } else {
                                            ""
                                        }),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (row.model.tags.isNotEmpty()) {
                            Text(
                                row.model.tags.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (row.model.description.isNotBlank()) {
                            Text(row.model.description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.downloaded && row.localPath != null) {
                            Text(
                                row.localPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val state = row.downloadState
                        if (state.status == DownloadStatus.DOWNLOADING) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(state.message, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Row-level action, not the page's primary one: the market is a
                            // list of hundreds, so a filled button per row is exactly the
                            // "wall of purple" UI_DESIGN 4.4 forbids.
                            if (row.downloaded) {
                                OutlinedToolButton(
                                    "已下载 · 注册",
                                    onClick = { vm.registerDownloaded(row.model) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                OutlinedToolButton(
                                    "下载",
                                    onClick = { vm.download(row.model) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

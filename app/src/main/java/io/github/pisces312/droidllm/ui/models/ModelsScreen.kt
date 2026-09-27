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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.engineapi.engineIdFromStorage
import io.github.pisces312.droidllm.ui.components.ChoiceChipRow
import io.github.pisces312.droidllm.ui.components.DroidCard
import io.github.pisces312.droidllm.ui.components.EmptyState
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
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
    var tab by remember { mutableIntStateOf(0) }

    var displayName by remember { mutableStateOf("") }
    var engineId by remember { mutableStateOf(EngineId.LLAMACPP) }
    var showBrowser by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("模型管理", style = MaterialTheme.typography.titleLarge)
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
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("已导入") })
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
            LocalModelsTab(
                models = models,
                pendingPath = pendingPath,
                displayName = displayName,
                engineId = engineId,
                onDisplayName = { displayName = it },
                onEngineId = { engineId = it },
                onPendingPath = { vm.setPendingPath(it) },
                onBrowse = { showBrowser = true },
                onAdd = {
                    vm.add(engineId, displayName.trim(), pendingPath.trim())
                    displayName = ""
                },
                onImport = {
                    vm.importToModelRoot(engineId, displayName.trim(), pendingPath.trim())
                    displayName = ""
                },
                onScanRoot = vm::syncFoundModels,
                formatHint = vm.engineFormatHint(engineId),
                modelRoot = modelRoot.ifEmpty { vm.modelRoot() },
                onValidate = vm::validate,
                onDelete = vm::delete,
                onGoToMarket = { tab = 1 },
            )
        } else {
            MarketTab(vm = vm)
        }
        Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun LocalModelsTab(
    models: List<io.github.pisces312.droidllm.engineapi.LocalModel>,
    pendingPath: String,
    displayName: String,
    engineId: EngineId,
    onDisplayName: (String) -> Unit,
    onEngineId: (EngineId) -> Unit,
    onPendingPath: (String) -> Unit,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onScanRoot: () -> Unit,
    formatHint: String,
    modelRoot: String,
    onValidate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onGoToMarket: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            DroidCard {
                Column(Modifier.padding(12.dp)) {
                    Text("添加 / 导入第三方模型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
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
                        label = { Text("源文件或目录绝对路径") },
                        trailingIcon = {
                            IconButton(onClick = onBrowse) {
                                Icon(Icons.Filled.Folder, contentDescription = "浏览…")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // One filled action per card: importing. Browsing is a helper for the
                    // field above it, and referencing in place is the secondary path.
                    PrimaryButton(
                        text = "导入并复制到模型目录",
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedToolButton(
                        text = "仅引用原路径",
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedToolButton(
                        "扫描模型根目录",
                        onClick = onScanRoot,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "把当前根目录下已存在的 Modelscope/HF 下载登记到下方列表（对话框型模型跳过）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "市场下载（HF/魔塔）统一放在「$modelRoot」下 `{hf|modelscope}/models--org--repo/snapshots/`；" +
                            "第三方导入则复制到 `{根}/{引擎子目录}/`。四种引擎格式互不通用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (models.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Folder,
                    text = "还没有模型。可从模型市场下载，或用上面的卡片导入本地文件。",
                    actionLabel = "浏览模型市场",
                    onAction = onGoToMarket,
                )
            }
        }
        items(models) { model ->
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

@Composable
private fun MarketTab(vm: ModelsViewModel) {
    val source by vm.source.collectAsState()
    val downloaded by vm.downloadedIds.collectAsState()
    val downloadStates by vm.downloadStates.collectAsState()
    val downloadFilter by vm.downloadFilter.collectAsState()
    val rows = remember(source, downloaded, downloadStates, downloadFilter) {
        vm.catalogRows(downloadFilter)
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "HF官方 = huggingface.co，HF镜像 = hf-mirror.com，ModelScope = modelscope.cn。" +
                    "下载统一存 `{hf|modelscope}/models--org--repo/snapshots/`（MnnLlmChat 同款）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // One refresh for the whole market instead of a copy inside every row.
            OutlinedToolButton("刷新状态", onClick = { vm.refreshDownloaded() }, height = 40.dp)
        }
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
                                    "已下载 · 添加到列表",
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

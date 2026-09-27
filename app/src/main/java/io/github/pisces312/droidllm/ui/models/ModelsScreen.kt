package io.github.pisces312.droidllm.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pisces312.droidllm.data.catalog.DownloadStatus
import io.github.pisces312.droidllm.data.catalog.ModelSource
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton

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
        Text(
            "共享模型根目录：${modelRoot.ifEmpty { vm.modelRoot() }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("添加 / 导入第三方模型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    EngineIdDropdown(selected = engineId, onSelected = onEngineId)
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = "浏览…",
                            onClick = onBrowse,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedToolButton(
                            text = "仅引用原路径",
                            onClick = onAdd,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton(
                        text = "导入并复制到模型目录",
                        onClick = onImport,
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
        items(models) { model ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${model.engineId} · ${model.formatHint ?: "-"}" +
                            (model.quantHint?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (val loc = model.location) {
                            is ModelLocation.FilePath -> loc.path
                            is ModelLocation.SafUri -> loc.uri
                            is ModelLocation.AppPrivate -> loc.relativePath
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelSource.entries.forEach { s ->
                if (s == source) {
                    PrimaryButton(s.displayName, onClick = { vm.setSource(s) }, modifier = Modifier.weight(1f))
                } else {
                    OutlinedToolButton(s.displayName, onClick = { vm.setSource(s) }, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadFilter.entries.forEach { f ->
                val label = when (f) {
                    DownloadFilter.ALL -> "全部"
                    DownloadFilter.DOWNLOADED -> "已下载"
                    DownloadFilter.NOT_DOWNLOADED -> "未下载"
                }
                if (f == downloadFilter) {
                    PrimaryButton(label, onClick = { vm.setDownloadFilter(f) }, modifier = Modifier.weight(1f))
                } else {
                    OutlinedToolButton(label, onClick = { vm.setDownloadFilter(f) }, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "HF官方 = huggingface.co，HF镜像 = hf-mirror.com，ModelScope = modelscope.cn。" +
                "下载统一存 `{hf|modelscope}/models--org--repo/snapshots/`（MnnLlmChat 同款）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.model.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.model.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${row.model.engine} · ${row.model.vendor}" +
                                (if (row.model.sizeBytes > 0) {
                                    " · " + formatSize(row.model.sizeBytes)
                                } else {
                                    ""
                                }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            if (row.downloaded) {
                                PrimaryButton(
                                    "已下载 · 添加到列表",
                                    onClick = { vm.registerDownloaded(row.model) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                PrimaryButton(
                                    "下载",
                                    onClick = { vm.download(row.model) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedToolButton(
                                "刷新状态",
                                onClick = { vm.refreshDownloaded() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}

@Composable
private fun EngineIdDropdown(selected: EngineId, onSelected: (EngineId) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EngineId.entries.filter { it != EngineId.FAKE }.forEach { id ->
            val isSelected = id == selected
            if (isSelected) {
                PrimaryButton(
                    text = id.name,
                    onClick = { onSelected(id) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                OutlinedToolButton(
                    text = id.name,
                    onClick = { onSelected(id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.ModelLocation
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton

@Composable
fun ModelsScreen(vm: ModelsViewModel = hiltViewModel()) {
    val models by vm.models.collectAsState()
    val message by vm.message.collectAsState()
    val pendingPath by vm.pendingPath.collectAsState()

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
            "每个引擎独立配置模型。LiteRT：.litertlm/.task；MNN 目录（config.json+llm.mnn）；" +
                "Genie 目录（genie_config.json+tokenizer.json+*.bin）；llama.cpp：.gguf",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("添加模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                EngineIdDropdown(selected = engineId, onSelected = { engineId = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pendingPath,
                    onValueChange = { vm.setPendingPath(it) },
                    label = { Text("文件或目录绝对路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "浏览…",
                        onClick = { showBrowser = true },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedToolButton(
                        text = "校验并添加",
                        onClick = { vm.add(engineId, displayName.trim(), pendingPath.trim()) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "主路径：绝对路径 + 内置浏览器（需「所有文件访问」权限）。SAF 可选，暂未接入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.labelMedium,
                color = if (message.startsWith("校验失败") || message.startsWith("格式") ||
                    message.startsWith("路径") || message.startsWith("显示名")
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                onClick = { vm.validate(model.id) },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedToolButton(
                                "删除",
                                onClick = { vm.delete(model.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showBrowser) {
        FileBrowserDialog(
            engineId = engineId,
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

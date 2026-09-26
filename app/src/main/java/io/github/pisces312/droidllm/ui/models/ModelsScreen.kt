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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun ModelsScreen(vm: ModelsViewModel = hiltViewModel()) {
    val models by vm.models.collectAsState()
    val message by vm.message.collectAsState()

    var path by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var engineId by remember { mutableStateOf(EngineId.LLAMACPP) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Text("模型管理", style = MaterialTheme.typography.titleLarge)
        Text(
            "每个引擎独立配置自己的模型文件/目录。格式：LiteRT .litertlm/.task，MNN 目录(config.json+llm.mnn)，Genie 目录(genie_config.json+tokenizer.json+*.bin)，llama.cpp .gguf",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        Text("添加模型", style = MaterialTheme.typography.titleMedium)
        EngineIdDropdown(selected = engineId, onSelected = { engineId = it })
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("显示名") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("文件或目录绝对路径") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.add(engineId, displayName.trim(), path.trim()) }) {
                Text("校验并添加")
            }
            OutlinedButton(onClick = { vm.pickSaf(engineId, displayName.trim()) }) {
                Text("SAF 选择…")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(models) { model ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                        Text("${model.engineId} · ${model.formatHint ?: "-"}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            when (val loc = model.location) {
                                is io.github.pisces312.droidllm.engineapi.ModelLocation.FilePath -> loc.path
                                is io.github.pisces312.droidllm.engineapi.ModelLocation.SafUri -> loc.uri
                                is io.github.pisces312.droidllm.engineapi.ModelLocation.AppPrivate -> loc.relativePath
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.validate(model.id) }) { Text("校验") }
                            OutlinedButton(onClick = { vm.delete(model.id) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineIdDropdown(selected: EngineId, onSelected: (EngineId) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EngineId.entries.forEach { id ->
            OutlinedButton(
                onClick = { onSelected(id) },
                enabled = id != EngineId.FAKE,
            ) {
                Text(
                    id.name,
                    color = if (id == selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

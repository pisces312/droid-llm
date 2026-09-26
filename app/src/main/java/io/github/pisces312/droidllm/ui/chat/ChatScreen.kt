package io.github.pisces312.droidllm.ui.chat

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pisces312.droidllm.engineapi.Availability
import io.github.pisces312.droidllm.engineapi.ChatMessage
import io.github.pisces312.droidllm.engineapi.ChatRole

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val engines by vm.engines.collectAsState()
    val selectedEngine by vm.selectedEngine.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val availability by vm.availability.collectAsState()

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        EngineModelPickers(
            engines = engines,
            selectedEngine = selectedEngine,
            onEngine = vm::selectEngine,
            models = models,
            selectedModel = selectedModel,
            onModel = vm::selectModel,
            availability = availability,
        )
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { msg ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            msg.role.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Composer(onSend = vm::send)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineModelPickers(
    engines: List<EngineChoice>,
    selectedEngine: EngineChoice?,
    onEngine: (EngineChoice) -> Unit,
    models: List<ModelChoice>,
    selectedModel: ModelChoice?,
    onModel: (ModelChoice) -> Unit,
    availability: String,
) {
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = engineExpanded,
            onExpandedChange = { engineExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedEngine?.displayName ?: "Engine",
                onValueChange = {},
                readOnly = true,
                label = { Text("Engine") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) },
                modifier = Modifier.menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false },
            ) {
                engines.forEach { e ->
                    DropdownMenuItem(
                        text = { Text(e.displayName + if (e.available) "" else " (unavailable)") },
                        onClick = {
                            onEngine(e)
                            engineExpanded = false
                        },
                    )
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedModel?.displayName ?: "Model",
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                modifier = Modifier.menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.displayName) },
                        onClick = {
                            onModel(m)
                            modelExpanded = false
                        },
                    )
                }
            }
        }
    }
    Text(availability, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入消息…") },
        )
        Button(
            onClick = {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    onSend(t)
                    text = ""
                }
            },
        ) {
            Text("发送")
        }
    }
}

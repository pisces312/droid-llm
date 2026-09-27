package io.github.pisces312.droidllm.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.ConfigApplicability
import io.github.pisces312.droidllm.engineapi.ConfigField
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.ui.components.MetricPill
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.theme.DroidTheme

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val engines by vm.engines.collectAsState()
    val selectedEngine by vm.selectedEngine.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val availability by vm.availability.collectAsState()
    val sampling by vm.sampling.collectAsState()
    val generating by vm.generating.collectAsState()

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        TopBar(
            engines = engines,
            selectedEngine = selectedEngine,
            onEngine = vm::selectEngine,
            models = models,
            selectedModel = selectedModel,
            onModel = vm::selectModel,
            onNewSession = vm::newSession,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            availability + " · " + status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SamplingPanel(
            engineId = selectedEngine?.engine?.id,
            sampling = sampling,
            onUpdate = vm::updateSampling,
        )
        Spacer(Modifier.height(8.dp))
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "先在「模型」页添加模型，或使用 Fake 引擎试用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { msg ->
                    MessageBubble(msg)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Composer(
            generating = generating,
            onSend = vm::send,
            onStop = vm::stopGenerate,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    engines: List<EngineChoice>,
    selectedEngine: EngineChoice?,
    onEngine: (EngineChoice) -> Unit,
    models: List<ModelChoice>,
    selectedModel: ModelChoice?,
    onModel: (ModelChoice) -> Unit,
    onNewSession: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EngineModelPickers(
            engines = engines,
            selectedEngine = selectedEngine,
            onEngine = onEngine,
            models = models,
            selectedModel = selectedModel,
            onModel = onModel,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("新建会话") },
                    onClick = {
                        menuOpen = false
                        onNewSession()
                    },
                )
            }
        }
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
    modifier: Modifier = Modifier,
) {
    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = engineExpanded,
            onExpandedChange = { engineExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedEngine?.displayName ?: "引擎",
                onValueChange = {},
                readOnly = true,
                label = { Text("引擎") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false },
            ) {
                engines.forEach { e ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                e.displayName + if (e.available) "" else "（不可用）",
                                color = if (e.available) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    DroidTheme.extra.textDisabled
                                },
                            )
                        },
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
                value = selectedModel?.displayName ?: "模型",
                onValueChange = {},
                readOnly = true,
                label = { Text("模型") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("暂无模型，先到「模型」页添加") },
                        onClick = { modelExpanded = false },
                        enabled = false,
                    )
                }
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
}

@Composable
private fun MessageBubble(msg: ChatUiMessage) {
    val isUser = msg.role == ChatRole.USER
    val extra = DroidTheme.extra
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surface,
                )
                .padding(12.dp),
        ) {
            Text(
                if (isUser) "你" else "助手",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(msg.content, style = MaterialTheme.typography.bodyMedium)
            if (msg.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "· " + msg.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!isUser && (msg.ttftMs != null || msg.decodeTps != null)) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    msg.ttftMs?.let {
                        MetricPill("TTFT", "${it}ms")
                    }
                    msg.decodeTps?.let {
                        MetricPill("tok/s", "%.1f".format(it))
                    }
                }
            }
        }
    }
}

@Composable
private fun SamplingPanel(
    engineId: EngineId?,
    sampling: SamplingUiState,
    onUpdate: ((SamplingUiState) -> SamplingUiState) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起采样参数" else "展开采样参数")
            }
            if (expanded) {
                val id = engineId
                Text(
                    "默认值来自「设置」页；灰显字段当前引擎不生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NumberField(
                    label = "temperature",
                    value = sampling.temperature.toString(),
                    enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TEMPERATURE),
                    note = id?.let { ConfigApplicability.note(it, ConfigField.TEMPERATURE) },
                    onChange = { v -> onUpdate { it.copy(temperature = v.toFloatOrNull() ?: it.temperature) } },
                )
                NumberField(
                    label = "top_k",
                    value = sampling.topK.toString(),
                    enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TOP_K),
                    note = id?.let { ConfigApplicability.note(it, ConfigField.TOP_K) },
                    onChange = { v -> onUpdate { it.copy(topK = v.toIntOrNull() ?: it.topK) } },
                )
                NumberField(
                    label = "top_p",
                    value = sampling.topP.toString(),
                    enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TOP_P),
                    note = id?.let { ConfigApplicability.note(it, ConfigField.TOP_P) },
                    onChange = { v -> onUpdate { it.copy(topP = v.toFloatOrNull() ?: it.topP) } },
                )
                NumberField(
                    label = "threads",
                    value = sampling.threads.toString(),
                    enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.THREADS),
                    note = id?.let { ConfigApplicability.note(it, ConfigField.THREADS) },
                    onChange = { v -> onUpdate { it.copy(threads = v.toIntOrNull() ?: it.threads) } },
                )
                NumberField(
                    label = "maxNewTokens",
                    value = sampling.maxNewTokens.toString(),
                    enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.MAX_NEW_TOKENS),
                    note = id?.let { ConfigApplicability.note(it, ConfigField.MAX_NEW_TOKENS) },
                    onChange = { v -> onUpdate { it.copy(maxNewTokens = v.toIntOrNull() ?: it.maxNewTokens) } },
                )
                BackendField(
                    selected = sampling.backend,
                    engineId = id,
                    onSelect = { b -> onUpdate { it.copy(backend = b) } },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean,
    note: String?,
    onChange: (String) -> Unit,
) {
    Column(Modifier.alpha(if (enabled) 1f else 0.45f)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!enabled && note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendField(
    selected: Backend,
    engineId: EngineId?,
    onSelect: (Backend) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val supported = engineId?.let { ConfigApplicability.supportedBackends(it) } ?: Backend.entries
    val note = engineId?.let { ConfigApplicability.backendNote(it) }
    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("backend") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                supported.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(b.name) },
                        onClick = {
                            onSelect(b)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Composer(
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入消息…") },
            maxLines = 4,
        )
        if (generating) {
            PrimaryButton(
                text = "停止",
                onClick = onStop,
                modifier = Modifier.widthIn(min = 88.dp),
            )
        } else {
            PrimaryButton(
                text = "发送",
                onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        onSend(t)
                        text = ""
                    }
                },
                modifier = Modifier.widthIn(min = 88.dp),
            )
        }
    }
}

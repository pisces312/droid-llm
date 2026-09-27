package io.github.pisces312.droidllm.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ChatRole
import io.github.pisces312.droidllm.engineapi.ConfigApplicability
import io.github.pisces312.droidllm.engineapi.ConfigField
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.ui.components.ChoiceChipRow
import io.github.pisces312.droidllm.ui.components.EmptyState
import io.github.pisces312.droidllm.ui.components.LabeledDropdown
import io.github.pisces312.droidllm.ui.components.MetricPill
import io.github.pisces312.droidllm.ui.components.NumericField
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.components.SheetTitle
import io.github.pisces312.droidllm.ui.components.StatusDot
import io.github.pisces312.droidllm.ui.components.StatusDotState
import io.github.pisces312.droidllm.ui.components.formatModelSize
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    onGoToModels: () -> Unit = {},
    vm: ChatViewModel = hiltViewModel(),
) {
    val engines by vm.engines.collectAsState()
    val selectedEngine by vm.selectedEngine.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val messages by vm.messages.collectAsState()
    val status by vm.status.collectAsState()
    val availability by vm.availability.collectAsState()
    val sampling by vm.sampling.collectAsState()
    val generating by vm.generating.collectAsState()
    val sessionState by vm.sessionState.collectAsState()
    val canStart = selectedModel != null && selectedEngine?.available == true
    val canSend = sessionState == SessionState.READY && !generating
    val loading = sessionState == SessionState.LOADING

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var switcherOpen by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }
    // A streamed reply *replaces* the last bubble instead of appending one, so keying on
    // messages.size never fires mid-reply and the list stops following. Key on the tail's
    // length instead, and only follow while the user is still at the bottom.
    val lastIndex = messages.lastIndex
    val tailLength = messages.lastOrNull()?.content?.length ?: 0
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (
                last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset
                )
        }
    }
    LaunchedEffect(lastIndex, tailLength) {
        if (lastIndex >= 0 && atBottom) listState.animateScrollToItem(lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            ScopeBar(
                engine = selectedEngine,
                model = selectedModel,
                busy = loading || generating,
                inUse = sessionState == SessionState.READY,
                onClick = {
                    // The picker sits above the keyboard, so drop the input focus first.
                    focusManager.clearFocus()
                    switcherOpen = true
                },
            )
            Spacer(Modifier.height(6.dp))
            StatusLine(
                text = availability + " · " + status,
                state = sessionState,
                canStart = canStart,
                onStart = vm::startModel,
                onStop = vm::stopModel,
                onNewSession = vm::newSession,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (messages.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        text = "先在「模型」页添加模型，再回到这里启动它",
                        actionLabel = "去「模型」页",
                        onAction = onGoToModels,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages) { msg ->
                            MessageBubble(msg)
                        }
                    }
                    if (!atBottom) {
                        FilledTonalIconButton(
                            onClick = { scope.launch { listState.animateScrollToItem(lastIndex) } },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "回到底部")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Composer(
                summary = sampling.summary(),
                onOpenParams = {
                    focusManager.clearFocus()
                    paramsOpen = true
                },
                generating = generating,
                canSend = canSend,
                onSend = vm::send,
                onStop = vm::stopGenerate,
            )
            Spacer(Modifier.height(8.dp))
        }
        // Model load blocks the calling thread in every adapter and cannot be
        // cancelled midway, so block the whole page and report progress instead
        // of letting the user queue up conflicting actions.
        if (loading) {
            LoadingOverlay(modelName = selectedModel?.displayName)
        }
    }

    if (switcherOpen) {
        ScopeSheet(
            engines = engines,
            selectedEngine = selectedEngine,
            onEngine = vm::selectEngine,
            models = models,
            selectedModel = selectedModel,
            // Picking a model ends the flow, so close; picking an engine does not, because
            // the model list under it is what the user came for next.
            onModel = {
                vm.selectModel(it)
                switcherOpen = false
            },
            onDismiss = { switcherOpen = false },
        )
    }
    if (paramsOpen) {
        SamplingSheet(
            engineId = selectedEngine?.engine?.id,
            sampling = sampling,
            onUpdate = vm::updateSampling,
            onDismiss = { paramsOpen = false },
        )
    }
}

/** The summary shown on the parameter chip: the two values actually worth glancing at. */
private fun SamplingUiState.summary(): String =
    "temp $temperature · top_p $topP · tok $maxNewTokens"

/**
 * The one row that says what the next message will run on: status dot, engine, model.
 *
 * Deliberately a single entry point rather than two dropdowns. Engine and model are not
 * independent choices — a model belongs to exactly one engine (DESIGN §1.2) — and two
 * parallel pickers hid that hierarchy while truncating every long model name.
 *
 * Tapping the row opens [ScopeSheet]; the engine is never switched from here, otherwise
 * this would be a second engine entry point again.
 */
@Composable
private fun ScopeBar(
    engine: EngineChoice?,
    model: ModelChoice?,
    busy: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
) {
    val dot = when {
        engine?.available != true -> StatusDotState.UNAVAILABLE
        busy -> StatusDotState.BUSY
        else -> StatusDotState.OK
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DroidTheme.extra.surfaceHigh)
            .clickable(onClickLabel = "选择引擎与模型", onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Filled dot = this model is loaded; ring = usable but idle (UI_DESIGN §7.2).
        StatusDot(dot, solid = inUse)
        Text(
            engine?.displayName ?: "选择引擎",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "·",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            model?.displayName ?: "未选择模型",
            style = MaterialTheme.typography.bodyMedium,
            color = if (model == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Engine + model picker. One panel covers both levels: the engine chip row reshapes the
 * list underneath it, so the hierarchy the data actually has is visible while choosing.
 *
 * Selecting here still only *selects* — per DESIGN §1.2 it releases the running session
 * and returns to IDLE, and loading stays behind the 启动 button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSheet(
    engines: List<EngineChoice>,
    selectedEngine: EngineChoice?,
    onEngine: (EngineChoice) -> Unit,
    models: List<ModelChoice>,
    selectedModel: ModelChoice?,
    onModel: (ModelChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            SheetTitle(
                text = "选择引擎与模型",
                subtitle = "切换会释放当前已加载的模型；加载仍由「启动」触发。",
            )
            Spacer(Modifier.height(12.dp))
            ChoiceChipRow(
                options = engines,
                selected = selectedEngine,
                label = { it.displayName },
                dimmed = { !it.available },
                leading = { choice ->
                    StatusDot(
                        if (choice.available) StatusDotState.OK else StatusDotState.UNAVAILABLE,
                        solid = false,
                    )
                },
                onSelected = onEngine,
            )
            selectedEngine?.unavailableReason()?.let { reason ->
                Spacer(Modifier.height(6.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = DroidTheme.extra.warn,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                selectedEngine?.let { "${it.displayName} 的已配置模型" } ?: "先选择一个引擎",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (models.isEmpty()) {
                Text(
                    "该引擎还没有模型，先到「模型」页添加或下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(models, key = { it.model.id }) { choice ->
                        ScopeModelRow(
                            model = choice,
                            selected = choice.model.id == selectedModel?.model?.id,
                            onClick = { onModel(choice) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One pickable model: name, `引擎 · 量化 · 体积` subtitle, check when current. */
@Composable
private fun ScopeModelRow(model: ModelChoice, selected: Boolean, onClick: () -> Unit) {
    val m = model.model
    val subtitle = listOfNotNull(
        m.engineId.displayName,
        m.quantHint,
        m.fileSizeBytes?.takeIf { it > 0 }?.let { formatModelSize(it) },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Status sentence, start/stop, new session. */
@Composable
private fun StatusLine(
    text: String,
    state: SessionState,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        SessionToggleButton(
            state = state,
            canStart = canStart,
            onStart = onStart,
            onStop = onStop,
        )
        IconButton(onClick = onNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "新建会话（清空上下文）")
        }
    }
}

/**
 * Full-page scrim shown while [SessionState.LOADING]. Swallows touch input so
 * nothing else on the page can be triggered; the bottom navigation sits outside
 * this component and therefore stays reachable.
 */
@Composable
private fun LoadingOverlay(modelName: String?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    "正在加载模型…",
                    style = MaterialTheme.typography.titleSmall,
                )
                modelName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "首次加载需数十秒，请勿离开此页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Start / stop the chat model. Stopping releases it from memory; only a
 * [SessionState.READY] session accepts prompts.
 *
 * The filled start button and the filled send button never coexist: while nothing is
 * loaded, starting is the screen's action and send is greyed; once READY, start degrades
 * to an outlined stop so send can be the single filled action (UI_DESIGN.md §4.4).
 */
@Composable
private fun SessionToggleButton(
    state: SessionState,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    when (state) {
        SessionState.READY -> OutlinedToolButton("停止", onClick = onStop, height = 40.dp)
        SessionState.LOADING -> PrimaryButton(
            text = "加载中",
            onClick = {},
            enabled = false,
            height = 40.dp,
        )
        SessionState.IDLE, SessionState.FAILED -> PrimaryButton(
            text = if (state == SessionState.FAILED) "重试" else "启动",
            onClick = onStart,
            enabled = canStart,
            height = 40.dp,
        )
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
            val hasMetrics = !isUser &&
                (msg.ttftMs != null || msg.prefillTps != null || msg.decodeTps != null)
            if (hasMetrics) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    msg.ttftMs?.let {
                        MetricPill("TTFT", "${it}ms")
                    }
                    msg.prefillTps?.let {
                        MetricPill("prefill", "%.1f tok/s".format(it))
                    }
                }
                msg.decodeTps?.let {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetricPill("decode", "%.1f tok/s".format(it))
                        msg.perTokenMsP50?.let { p50 ->
                            MetricPill("p50", "%.0f ms".format(p50))
                        }
                    }
                }
                if (msg.promptTokens > 0 || msg.generatedTokens > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${msg.promptTokens} prompt → ${msg.generatedTokens} gen tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Sampling knobs, in a sheet opened from the parameter chip.
 *
 * A sheet rather than an inline collapsing card: collapsed, the card still cost a full-width
 * row above the transcript for something that is opened rarely, and it double-counted the tap
 * target (card + inner button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SamplingSheet(
    engineId: EngineId?,
    sampling: SamplingUiState,
    onUpdate: ((SamplingUiState) -> SamplingUiState) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SheetTitle(
                text = "采样参数",
                subtitle = "默认值来自「设置」页；灰显字段当前引擎不生效。",
            )
            Spacer(Modifier.height(12.dp))
            val id = engineId
            NumericField(
                label = "temp",
                value = sampling.temperature.toString(),
                decimal = true,
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TEMPERATURE),
                note = id?.let { ConfigApplicability.note(it, ConfigField.TEMPERATURE) },
                onCommit = { v ->
                    v.toFloatOrNull()?.let { n -> onUpdate { it.copy(temperature = n) } }
                },
            )
            NumericField(
                label = "top_k",
                value = sampling.topK.toString(),
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TOP_K),
                note = id?.let { ConfigApplicability.note(it, ConfigField.TOP_K) },
                onCommit = { v ->
                    v.toIntOrNull()?.let { n -> onUpdate { it.copy(topK = n) } }
                },
            )
            NumericField(
                label = "top_p",
                value = sampling.topP.toString(),
                decimal = true,
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TOP_P),
                note = id?.let { ConfigApplicability.note(it, ConfigField.TOP_P) },
                onCommit = { v ->
                    v.toFloatOrNull()?.let { n -> onUpdate { it.copy(topP = n) } }
                },
            )
            NumericField(
                label = "threads",
                value = sampling.threads.toString(),
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.THREADS),
                note = id?.let { ConfigApplicability.note(it, ConfigField.THREADS) },
                onCommit = { v ->
                    v.toIntOrNull()?.let { n -> onUpdate { it.copy(threads = n) } }
                },
            )
            NumericField(
                label = "maxNewTokens",
                value = sampling.maxNewTokens.toString(),
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.MAX_NEW_TOKENS),
                note = id?.let { ConfigApplicability.note(it, ConfigField.MAX_NEW_TOKENS) },
                onCommit = { v ->
                    v.toIntOrNull()?.let { n -> onUpdate { it.copy(maxNewTokens = n) } }
                },
            )
            BackendField(
                selected = sampling.backend,
                engineId = id,
                onSelect = { b -> onUpdate { it.copy(backend = b) } },
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BackendField(
    selected: Backend,
    engineId: EngineId?,
    onSelect: (Backend) -> Unit,
) {
    val supported = engineId?.let { ConfigApplicability.supportedBackends(it) } ?: Backend.entries
    val note = engineId?.let { ConfigApplicability.backendNote(it) }
    Column {
        Spacer(Modifier.height(8.dp))
        LabeledDropdown(
            label = "backend",
            options = supported.map { it.name to it.name },
            selectedKey = selected.name,
            onSelected = { onSelect(Backend.valueOf(it)) },
            emptyText = "—",
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Parameter chip + input + send.
 *
 * Only the filled send button lives here; starting the model is handled by the status line
 * above, and the two never appear filled at the same time.
 */
@Composable
private fun Composer(
    summary: String,
    onOpenParams: () -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(
            onClick = onOpenParams,
            label = {
                Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (canSend || generating) "输入消息…" else "启动模型后可发送消息") },
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
                    enabled = canSend,
                    modifier = Modifier.widthIn(min = 88.dp),
                )
            }
        }
    }
}

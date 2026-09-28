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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
    val sampling by vm.sampling.collectAsState()
    val generating by vm.generating.collectAsState()
    val sessionState by vm.sessionState.collectAsState()
    val thinkingSupported by vm.thinkingSupported.collectAsState()
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
            // The picker expands downward from this bar so the hand stays near the
            // tap target (was a bottom sheet under a top trigger).
            var scopeAnchor by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { scopeAnchor = it.size },
            ) {
                ScopeBar(
                    engine = selectedEngine,
                    model = selectedModel,
                    busy = loading || generating,
                    inUse = sessionState == SessionState.READY,
                    onClick = {
                        // Drop input focus so the IME does not sit under the panel.
                        focusManager.clearFocus()
                        switcherOpen = true
                    },
                )
                if (switcherOpen) {
                    ScopeDropdown(
                        anchorWidthPx = scopeAnchor.width,
                        anchorHeightPx = scopeAnchor.height,
                        engines = engines,
                        selectedEngine = selectedEngine,
                        onEngine = vm::selectEngine,
                        models = models,
                        selectedModel = selectedModel,
                        // Picking a model ends the flow, so close; picking an engine does not,
                        // because the model list under it is what the user came for next.
                        onModel = {
                            vm.selectModel(it)
                            switcherOpen = false
                        },
                        onDismiss = { switcherOpen = false },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            StatusLine(
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
                thinkingSupported = thinkingSupported,
                thinkingEnabled = sampling.enableThinking,
                onThinkingChange = vm::setThinking,
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

    if (paramsOpen) {
        SamplingSheet(
            engineId = selectedEngine?.engine?.id,
            modelName = selectedModel?.displayName,
            sampling = sampling,
            onUpdate = vm::updateSampling,
            onOverride = vm::setModelOverride,
            onClearOverrides = vm::clearModelOverride,
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
 * Tapping the row opens [ScopeDropdown]; the engine is never switched from here, otherwise
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
 * Engine + model picker, anchored under the [ScopeBar] as a downward dropdown.
 *
 * A bottom sheet put the choices half a screen away from the tap target at the top;
 * this panel opens right under the bar so the hand stays put. One panel covers both
 * levels: the engine chip row reshapes the list underneath it, so the hierarchy the
 * data actually has is visible while choosing.
 *
 * Selecting here still only *selects* — per DESIGN §1.2 it releases the running session
 * and returns to IDLE, and loading stays behind the 启动 button.
 */
@Composable
private fun ScopeDropdown(
    anchorWidthPx: Int,
    anchorHeightPx: Int,
    engines: List<EngineChoice>,
    selectedEngine: EngineChoice?,
    onEngine: (EngineChoice) -> Unit,
    models: List<ModelChoice>,
    selectedModel: ModelChoice?,
    onModel: (ModelChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, anchorHeightPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .width(with(density) { anchorWidthPx.toDp() })
                .heightIn(max = 420.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(models, key = { it.model.id }) { choice ->
                            ScopeModelRow(
                                model = choice,
                                selected = choice.model.id == selectedModel?.model?.id,
                                onClick = { onModel(choice) },
                            )
                        }
                    }
                }
            }
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

/** Start/stop and new session. Status wording lives in the scope bar's dot + transient snackbars. */
@Composable
private fun StatusLine(
    state: SessionState,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
    ) {
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
            // Drop empty / off-mode thinking blocks so raw tags never appear in the bubble.
            Text(
                ThinkingDisplay.forDisplay(msg.content, msg.thinkingEnabled),
                style = MaterialTheme.typography.bodyMedium,
            )
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
 *
 * Every field has a "仅本模型" pin. Unpinned edits write the global default in
 * Settings; pinned edits write only the selected model's overlay, so one model
 * can diverge without moving every other model's baseline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SamplingSheet(
    engineId: EngineId?,
    modelName: String?,
    sampling: SamplingUiState,
    onUpdate: ((SamplingUiState) -> SamplingUiState) -> Unit,
    onOverride: (ParamsField, FieldValue?) -> Unit,
    onClearOverrides: () -> Unit,
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
                subtitle = if (modelName == null) {
                    "默认值来自「设置」页；灰显字段当前引擎不生效。"
                } else {
                    "默认值来自「设置」页。点「仅本模型」可让 $modelName 单独覆盖该项。"
                },
            )
            Spacer(Modifier.height(12.dp))
            val id = engineId
            val pinned = sampling.overriddenFields
            val pinEnabled = modelName != null

            OverrideField(
                label = "temp",
                value = sampling.temperature.toString(),
                decimal = true,
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TEMPERATURE),
                note = id?.let { ConfigApplicability.note(it, ConfigField.TEMPERATURE) },
                pinned = ParamsField.TEMPERATURE in pinned,
                pinEnabled = pinEnabled,
                onTogglePin = { on ->
                    onOverride(
                        ParamsField.TEMPERATURE,
                        if (on) FieldValue.Dec(sampling.temperature) else null,
                    )
                },
                onCommit = { v ->
                    v.toFloatOrNull()?.let { n ->
                        if (ParamsField.TEMPERATURE in pinned) {
                            onOverride(ParamsField.TEMPERATURE, FieldValue.Dec(n))
                        } else {
                            onUpdate { it.copy(temperature = n) }
                        }
                    }
                },
            )
            OverrideField(
                label = "top_k",
                value = sampling.topK.toString(),
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TOP_K),
                note = id?.let { ConfigApplicability.note(it, ConfigField.TOP_K) },
                pinned = ParamsField.TOP_K in pinned,
                pinEnabled = pinEnabled,
                onTogglePin = { on ->
                    onOverride(
                        ParamsField.TOP_K,
                        if (on) FieldValue.Num(sampling.topK) else null,
                    )
                },
                onCommit = { v ->
                    v.toIntOrNull()?.let { n ->
                        if (ParamsField.TOP_K in pinned) {
                            onOverride(ParamsField.TOP_K, FieldValue.Num(n))
                        } else {
                            onUpdate { it.copy(topK = n) }
                        }
                    }
                },
            )
            OverrideField(
                label = "top_p",
                value = sampling.topP.toString(),
                decimal = true,
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.TOP_P),
                note = id?.let { ConfigApplicability.note(it, ConfigField.TOP_P) },
                pinned = ParamsField.TOP_P in pinned,
                pinEnabled = pinEnabled,
                onTogglePin = { on ->
                    onOverride(
                        ParamsField.TOP_P,
                        if (on) FieldValue.Dec(sampling.topP) else null,
                    )
                },
                onCommit = { v ->
                    v.toFloatOrNull()?.let { n ->
                        if (ParamsField.TOP_P in pinned) {
                            onOverride(ParamsField.TOP_P, FieldValue.Dec(n))
                        } else {
                            onUpdate { it.copy(topP = n) }
                        }
                    }
                },
            )
            OverrideField(
                label = "threads",
                value = sampling.threads.toString(),
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.THREADS),
                note = id?.let { ConfigApplicability.note(it, ConfigField.THREADS) },
                pinned = ParamsField.THREADS in pinned,
                pinEnabled = pinEnabled,
                onTogglePin = { on ->
                    onOverride(
                        ParamsField.THREADS,
                        if (on) FieldValue.Num(sampling.threads) else null,
                    )
                },
                onCommit = { v ->
                    v.toIntOrNull()?.let { n ->
                        if (ParamsField.THREADS in pinned) {
                            onOverride(ParamsField.THREADS, FieldValue.Num(n))
                        } else {
                            onUpdate { it.copy(threads = n) }
                        }
                    }
                },
            )
            OverrideField(
                label = "maxNewTokens",
                value = sampling.maxNewTokens.toString(),
                enabled = id == null || ConfigApplicability.isApplicable(id, ConfigField.MAX_NEW_TOKENS),
                note = id?.let { ConfigApplicability.note(it, ConfigField.MAX_NEW_TOKENS) },
                pinned = ParamsField.MAX_NEW_TOKENS in pinned,
                pinEnabled = pinEnabled,
                onTogglePin = { on ->
                    onOverride(
                        ParamsField.MAX_NEW_TOKENS,
                        if (on) FieldValue.Num(sampling.maxNewTokens) else null,
                    )
                },
                onCommit = { v ->
                    v.toIntOrNull()?.let { n ->
                        if (ParamsField.MAX_NEW_TOKENS in pinned) {
                            onOverride(ParamsField.MAX_NEW_TOKENS, FieldValue.Num(n))
                        } else {
                            onUpdate { it.copy(maxNewTokens = n) }
                        }
                    }
                },
            )
            BackendField(
                selected = sampling.backend,
                engineId = id,
                pinned = ParamsField.BACKEND in pinned,
                pinEnabled = pinEnabled,
                onTogglePin = { on ->
                    onOverride(
                        ParamsField.BACKEND,
                        if (on) FieldValue.BackendValue(sampling.backend) else null,
                    )
                },
                onSelect = { b ->
                    if (ParamsField.BACKEND in pinned) {
                        onOverride(ParamsField.BACKEND, FieldValue.BackendValue(b))
                    } else {
                        onUpdate { it.copy(backend = b) }
                    }
                },
            )
            if (sampling.hasOverrides && modelName != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedToolButton(
                    "清除 $modelName 的全部单项覆盖",
                    onClick = onClearOverrides,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

/** A [NumericField] with a trailing "仅本模型" pin toggle. */
@Composable
private fun OverrideField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    decimal: Boolean = false,
    enabled: Boolean = true,
    note: String? = null,
    pinned: Boolean = false,
    pinEnabled: Boolean = true,
    onTogglePin: (Boolean) -> Unit,
) {
    Column {
        NumericField(
            label = label,
            value = value,
            decimal = decimal,
            enabled = enabled,
            note = note,
            onCommit = onCommit,
        )
        PinRow(pinned = pinned, enabled = pinEnabled, onToggle = onTogglePin)
    }
}

@Composable
private fun PinRow(pinned: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    if (!enabled) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (pinned) {
            Text(
                "已覆盖全局默认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedToolButton(
            text = if (pinned) "取消覆盖" else "仅本模型",
            onClick = { onToggle(!pinned) },
        )
    }
}

@Composable
private fun BackendField(
    selected: Backend,
    engineId: EngineId?,
    pinned: Boolean = false,
    pinEnabled: Boolean = true,
    onTogglePin: (Boolean) -> Unit = {},
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
        PinRow(pinned = pinned, enabled = pinEnabled, onToggle = onTogglePin)
    }
}

/**
 * Parameter chip + Thinking switch + input + send.
 *
 * Only the filled send button lives here; starting the model is handled by the status line
 * above, and the two never appear filled at the same time.
 *
 * The Thinking chip sits with the parameter chip (UI_REVIEW §3.2 模式 3: per-message
 * controls belong next to the composer). It is hidden when the selected model has no
 * thinking mode — a toggle that does nothing is worse than no toggle.
 */
@Composable
private fun Composer(
    summary: String,
    onOpenParams: () -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    thinkingSupported: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingChange: (Boolean) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (thinkingSupported) {
                FilterChip(
                    selected = thinkingEnabled,
                    onClick = { onThinkingChange(!thinkingEnabled) },
                    label = { Text(if (thinkingEnabled) "Thinking 开" else "Thinking 关") },
                    leadingIcon = {
                        if (thinkingEnabled) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        }
                    },
                )
            }
        }
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

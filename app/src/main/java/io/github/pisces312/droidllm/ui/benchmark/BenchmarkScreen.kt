package io.github.pisces312.droidllm.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.pisces312.droidllm.benchmark.BenchCaseId
import io.github.pisces312.droidllm.benchmark.BenchmarkPrompts
import io.github.pisces312.droidllm.benchmark.BenchmarkReport
import io.github.pisces312.droidllm.benchmark.CaseResult
import io.github.pisces312.droidllm.benchmark.TargetResult
import io.github.pisces312.droidllm.engineapi.displayName
import io.github.pisces312.droidllm.engineapi.engineIdFromStorage
import io.github.pisces312.droidllm.ui.components.EngineStatusCard
import io.github.pisces312.droidllm.ui.components.LabeledDropdown
import io.github.pisces312.droidllm.ui.components.MetricPill
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.components.ProgressHeader
import io.github.pisces312.droidllm.ui.components.ResultTable
import io.github.pisces312.droidllm.ui.components.TableCellModel
import io.github.pisces312.droidllm.ui.components.WarningBanner
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import io.github.pisces312.droidllm.ui.theme.MetricSmallType

/**
 * P4 benchmark page (UI_DESIGN.md §5.3): engine×model config → prompts/cases →
 * run → live progress → result table + disclaimer → JSON export.
 */
@Composable
fun BenchmarkScreen(
    onGoToModels: () -> Unit = {},
    vm: BenchmarkViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.onAppBackground()
                Lifecycle.Event.ON_RESUME -> vm.onAppForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.snackbar) {
        val msg = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.dismissSnackbar()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("评测", style = MaterialTheme.typography.titleLarge)

            if (state.showCoolingBanner) {
                WarningBanner(
                    text = "建议插电、静置冷却后再测；温度 >42℃ 仅警告，不会中断",
                    onDismiss = vm::dismissCoolingBanner,
                )
            }

            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }

            EngineConfigSection(state = state, vm = vm, onGoToModels = onGoToModels)

            PromptSection(state = state, vm = vm)

            CaseSection(state = state, vm = vm)

            ParamsSection(state = state, vm = vm)

            if (!state.running) {
                PrimaryButton(
                    "开始评测",
                    onClick = vm::startRun,
                    enabled = state.engineRows.any { it.included },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.running || state.paused) {
                ProgressHeader(
                    title = state.progressTitle,
                    detail = state.progressDetail,
                    fraction = state.progressFraction,
                    paused = state.paused,
                    onCancel = if (!state.paused) vm::cancel else null,
                    onResume = if (state.paused) vm::resume else null,
                    onAbandon = if (state.paused) vm::abandon else null,
                )
                state.lastDecodeTps?.let { tps ->
                    MetricPill(label = "最近 decode", value = "%.1f tok/s".format(tps))
                }
            }

            val report = state.report
            if (report != null) {
                ResultSection(report = report)
                OutlinedToolButton(
                    "导出 JSON",
                    onClick = vm::exportLatest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HistorySection(state = state, vm = vm)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EngineConfigSection(
    state: BenchmarkUiState,
    vm: BenchmarkViewModel,
    onGoToModels: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("引擎 × 模型", style = MaterialTheme.typography.titleMedium)
        if (state.engineRows.isEmpty()) {
            Text("正在探测引擎…", style = MaterialTheme.typography.labelMedium)
        }
        state.engineRows.forEach { row ->
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = row.included,
                            onCheckedChange = { vm.toggleEngine(row.engineId, it) },
                            enabled = row.available && row.models.isNotEmpty(),
                        )
                        EngineStatusCard(
                            name = row.engineName,
                            available = row.available,
                            statusText = row.availabilityLabel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.included) {
                        if (row.models.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "暂无模型，先到「模型」页添加",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onGoToModels) { Text("去 Models 页") }
                            }
                        } else {
                            LabeledDropdown(
                                label = "模型",
                                options = row.models.map { it.id to it.displayName },
                                selectedKey = row.selectedModelId,
                                onSelected = { vm.selectModel(row.engineId, it) },
                                emptyText = "暂无模型",
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptSection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("提示词（单选）", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchmarkPrompts.all.forEach { prompt ->
                FilterChip(
                    selected = state.promptId == prompt.id,
                    onClick = { vm.selectPrompt(prompt.id) },
                    label = { Text(prompt.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseSection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("用例（默认 L/P/D）", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchCaseId.entries.forEach { case ->
                val checked = state.cases.contains(case)
                FilterChip(
                    selected = checked,
                    onClick = { vm.toggleCase(case, !checked) },
                    label = { Text(case.label) },
                )
            }
        }
    }
}

@Composable
private fun ParamsSection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("运行参数", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = vm::toggleParamsExpanded) {
                    Text(if (state.paramsExpanded) "收起" else "展开")
                }
            }
            if (state.paramsExpanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "warmup=${state.warmup} · runs=${state.runs} · maxNewTokens=${state.maxNewTokens}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stepper("warmup", state.warmup, onMinus = { vm.setWarmup(state.warmup - 1) }, onPlus = { vm.setWarmup(state.warmup + 1) })
                    Stepper("runs", state.runs, onMinus = { vm.setRuns(state.runs - 1) }, onPlus = { vm.setRuns(state.runs + 1) })
                    Stepper(
                        "tokens",
                        state.maxNewTokens,
                        onMinus = { vm.setMaxNewTokens(state.maxNewTokens - 32) },
                        onPlus = { vm.setMaxNewTokens(state.maxNewTokens + 32) },
                    )
                }
            } else {
                Text(
                    "warmup=${state.warmup} · runs=${state.runs} · maxNewTokens=${state.maxNewTokens}（默认样本共 ${state.warmup + state.runs} 次）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onMinus) { Text("−") }
        Text("$label=$value", style = MetricSmallType, color = DroidTheme.extra.accent)
        TextButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun ResultSection(report: BenchmarkReport) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("结果表", style = MaterialTheme.typography.titleMedium)
        ResultTable(
            headers = listOf(
                "引擎", "模型", "Quant", "Load ms", "TTFT ms", "Prefill tok/s", "Decode tok/s",
                "RSS peak MB", "温度 ℃",
            ),
            rows = report.targets.map { it.toRow() },
        )
        Text(
            "跨模型/跨量化只作参考，不构成绝对快慢结论",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun TargetResult.toRow(): List<TableCellModel> {
    val err = error
    if (err != null) {
        val short = err.take(28)
        return listOf(
            TableCellModel(engineDisplayName),
            TableCellModel(model.displayName),
            TableCellModel(model.quantHint ?: "—"),
            TableCellModel(short, isError = true),
            TableCellModel("—"),
            TableCellModel("—"),
            TableCellModel("—"),
            TableCellModel(rssMbPeak?.toString() ?: "—", isMetric = true),
            TableCellModel(tempCStart?.let { "%.1f".format(it) } ?: "—", isMetric = true),
        )
    }
    return listOf(
        TableCellModel(engineDisplayName),
        TableCellModel(model.displayName),
        TableCellModel(model.quantHint ?: "—"),
        metricCell(cases[BenchCaseId.LOAD]?.loadMs ?: cases.values.firstNotNullOfOrNull { it.loadMs }),
        metricCell(cases.values.firstNotNullOfOrNull { it.ttftMs }),
        metricCell(cases.values.firstNotNullOfOrNull { it.prefillTps }),
        metricCell(cases.values.firstNotNullOfOrNull { it.decodeTps }),
        metricCell(rssMbPeak),
        TableCellModel(tempCStart?.let { "%.1f".format(it) } ?: "—", isMetric = true),
    )
}

private fun metricCell(v: Long?): TableCellModel =
    if (v == null) TableCellModel("—") else TableCellModel(v.toString(), isMetric = true)

private fun metricCell(v: Double?): TableCellModel =
    if (v == null) TableCellModel("—") else TableCellModel("%.1f".format(v), isMetric = true)

@Composable
private fun HistorySection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("历史记录", style = MaterialTheme.typography.titleMedium)
            if (state.history.isNotEmpty()) {
                TextButton(onClick = vm::clearHistory) { Text("清空") }
            }
        }
        if (state.history.isEmpty()) {
            Text("暂无历史", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.history.take(8).forEach { item ->
                // engineId is persisted as EngineId.name; resolve it for display.
                val engineLabel = engineIdFromStorage(item.engineId)?.displayName ?: item.engineId
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("$engineLabel · ${item.modelName}", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "decode=${item.decodeTps?.let { "%.1f".format(it) } ?: "—"} tok/s · " +
                                "ttft=${item.ttftMs ?: "—"} ms · rssPeak=${item.rssMbPeak ?: "—"} MB",
                            style = MetricSmallType,
                            color = DroidTheme.extra.accent,
                        )
                        if (!item.quantHint.isNullOrBlank()) {
                            Text("quant=${item.quantHint}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

package io.github.pisces312.droidllm.ui.benchmark

import androidx.compose.ui.res.stringResource
import io.github.pisces312.droidllm.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.pisces312.droidllm.ui.components.DroidCard
import io.github.pisces312.droidllm.ui.components.MetricPill
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.components.ProgressHeader
import io.github.pisces312.droidllm.ui.components.ResultTable
import io.github.pisces312.droidllm.ui.components.StatusDot
import io.github.pisces312.droidllm.ui.components.StatusDotState
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            // 8dp rather than 12dp: this page has six stacked sections before the start
            // button, and the extra 20dp was what kept it off the first screen.
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.nav_benchmark), style = MaterialTheme.typography.titleLarge)

            if (state.showCoolingBanner) {
                WarningBanner(
                    text = stringResource(R.string.bench_cooling_banner),
                    onDismiss = vm::dismissCoolingBanner,
                )
            }

            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }

            EngineConfigSection(state = state, vm = vm, onGoToModels = onGoToModels)

            PromptSection(state = state, vm = vm)

            CaseSection(state = state, vm = vm)

            if (!state.running) {
                PrimaryButton(
                    stringResource(R.string.bench_start),
                    onClick = vm::startRun,
                    enabled = state.engineRows.any { it.included },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Run parameters are optional tuning with sane defaults, so the start button
            // sits above them rather than below. That is also what keeps it on the first
            // screen once the cooling banner is up.
            ParamsSection(state = state, vm = vm)

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
                    MetricPill(label = stringResource(R.string.bench_last_decode), value = "%.1f tok/s".format(tps))
                }
            }

            val report = state.report
            if (report != null) {
                ResultSection(report = report)
                OutlinedToolButton(
                    stringResource(R.string.bench_export_json),
                    onClick = vm::exportLatest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HistorySection(state = state, vm = vm)

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One compact line per engine: checkbox, status dot, name, and the chosen model.
 *
 * This was a card per engine with a full dropdown inside. With four engines and the
 * default "every available engine is checked" it ran to roughly 540dp, which pushed
 * 「开始评测」 off the first screen. A line is ~52dp, so the whole engine×model matrix
 * fits in ~210dp and the model list opens from the line itself. Multi-select is kept:
 * a benchmark compares engines, so exactly one model may be pinned per engine.
 */
@Composable
private fun EngineConfigSection(
    state: BenchmarkUiState,
    vm: BenchmarkViewModel,
    onGoToModels: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bench_section_targets), style = MaterialTheme.typography.titleMedium)
        if (state.engineRows.isEmpty()) {
            Text(stringResource(R.string.bench_probing), style = MaterialTheme.typography.labelMedium)
            return@Column
        }
        DroidCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                state.engineRows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(start = 56.dp, end = 12.dp))
                    }
                    EngineRow(row = row, vm = vm, onGoToModels = onGoToModels)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineRow(
    row: BenchEngineRow,
    vm: BenchmarkViewModel,
    onGoToModels: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectable = row.available && row.models.isNotEmpty()
    val selected = row.models.firstOrNull { it.id == row.selectedModelId }
    val extra = DroidTheme.extra

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(end = 12.dp),
    ) {
        Checkbox(
            checked = row.included,
            onCheckedChange = { vm.toggleEngine(row.engineId, it) },
            enabled = selectable,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (row.included && selectable) expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = row.included && selectable)
                    .padding(vertical = 8.dp),
            ) {
                StatusDot(
                    state = if (row.available) StatusDotState.OK else StatusDotState.UNAVAILABLE,
                    solid = false,
                )
                Text(
                    row.engineName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                // The right side shows one of four things: the chosen model, the dropdown
                // arrow, a jump to the models page when this engine has none, or why the
                // engine cannot be used at all.
                if (row.included && selected != null) {
                    Text(
                        selected.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                when {
                    row.included && selectable -> Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.bench_pick_model),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A TextButton here would drag the line to 63dp and push 开始评测
                    // back off the first screen; the padded click target keeps the line
                    // at 52dp without shrinking the tap area to the glyph.
                    row.available && row.models.isEmpty() -> Text(
                        stringResource(R.string.action_go_models_page),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onGoToModels)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    else -> Text(
                        row.availabilityLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                row.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.displayName) },
                        onClick = {
                            vm.selectModel(row.engineId, model.id)
                            expanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bench_add_more_models)) },
                    onClick = {
                        expanded = false
                        onGoToModels()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptSection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bench_section_prompt), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchmarkPrompts.all.forEach { prompt ->
                FilterChip(
                    selected = state.promptId == prompt.id,
                    onClick = { vm.selectPrompt(prompt.id) },
                    label = { Text(stringResource(prompt.labelRes)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseSection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bench_section_cases), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchCaseId.entries.forEach { case ->
                val checked = state.cases.contains(case)
                FilterChip(
                    selected = checked,
                    onClick = { vm.toggleCase(case, !checked) },
                    label = { Text(stringResource(case.labelRes)) },
                )
            }
        }
    }
}

@Composable
private fun ParamsSection(state: BenchmarkUiState, vm: BenchmarkViewModel) {
    DroidCard {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.bench_section_params), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = vm::toggleParamsExpanded) {
                    Text(if (state.paramsExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand))
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
                    stringResource(R.string.bench_params_summary, state.warmup, state.runs, state.maxNewTokens, state.warmup + state.runs),
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
        Text(stringResource(R.string.bench_section_results), style = MaterialTheme.typography.titleMedium)
        ResultTable(
            headers = listOf(
                stringResource(R.string.bench_col_engine),
                stringResource(R.string.nav_models),
                "Quant", "Load ms", "TTFT ms", "Prefill tok/s", "Decode tok/s",
                "RSS peak MB",
                stringResource(R.string.bench_col_temp),
            ),
            rows = report.targets.map { it.toRow() },
        )
        Text(
            stringResource(R.string.bench_disclaimer),
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
            Text(stringResource(R.string.bench_section_history), style = MaterialTheme.typography.titleMedium)
            if (state.history.isNotEmpty()) {
                TextButton(onClick = vm::clearHistory) { Text(stringResource(R.string.action_clear)) }
            }
        }
        if (state.history.isEmpty()) {
            Text(stringResource(R.string.bench_no_history), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.history.take(8).forEach { item ->
                // engineId is persisted as EngineId.name; resolve it for display.
                val engineLabel = engineIdFromStorage(item.engineId)?.displayName ?: item.engineId
                DroidCard {
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

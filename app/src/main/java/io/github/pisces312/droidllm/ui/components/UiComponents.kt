package io.github.pisces312.droidllm.ui.components

import androidx.compose.ui.res.stringResource
import io.github.pisces312.droidllm.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import io.github.pisces312.droidllm.ui.theme.MetricSmallType

/**
 * Three-state engine indicator (UI_DESIGN.md §4.2).
 *
 * [solid] distinguishes "in use" from "installed but idle" without adding a second
 * colour: a filled dot means the engine currently holds a loaded model, a ring means
 * it is usable but idle.
 */
enum class StatusDotState { OK, BUSY, UNAVAILABLE }

@Composable
fun StatusDot(
    state: StatusDotState,
    modifier: Modifier = Modifier,
    solid: Boolean = true,
) {
    val extra = DroidTheme.extra
    val color = when (state) {
        StatusDotState.OK -> extra.ok
        StatusDotState.BUSY -> extra.warn
        StatusDotState.UNAVAILABLE -> extra.textDisabled
    }
    Box(
        modifier
            .size(if (solid) 8.dp else 10.dp)
            .then(
                if (solid) {
                    Modifier.background(color, CircleShape)
                } else {
                    Modifier.border(BorderStroke(1.5.dp, color), CircleShape)
                },
            ),
    )
}

/**
 * Single-choice row of filter chips.
 *
 * Chips wrap their content and the row scrolls horizontally, so long labels that are
 * product names (`LiteRT-LM`, `llama.cpp`, `ModelScope`) stay on one line. An equal-weight
 * button row instead breaks them mid-word.
 *
 * @param dimmed marks options that are present but unusable. It is presentation only —
 * the option stays clickable, because an unusable engine still has to open so its reason
 * can be read.
 * @param leading drawn before the label inside the chip; used for [StatusDot].
 */
@Composable
fun <T> ChoiceChipRow(
    options: List<T>,
    selected: T?,
    /** Runs inside the chip's composable scope, so it may call `stringResource`. */
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    dimmed: (T) -> Boolean = { false },
    leading: (@Composable (T) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isDimmed = dimmed(option)
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        leading?.invoke(option)
                        Text(label(option))
                    }
                },
                colors = if (isDimmed) {
                    FilterChipDefaults.filterChipColors(
                        labelColor = DroidTheme.extra.textDisabled,
                        selectedLabelColor = DroidTheme.extra.textDisabled,
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                },
            )
        }
    }
}

/** Title block at the top of a bottom sheet: one line of heading, one of context. */
@Composable
fun SheetTitle(
    text: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One row: status dot + engine name + status sentence. */
@Composable
fun EngineStatusCard(
    name: String,
    available: Boolean,
    statusText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val contentAlpha = if (available) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(if (available) StatusDotState.OK else StatusDotState.UNAVAILABLE)
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Outlined dropdown over a `key to label` list — models, backends, any small enum. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emptyText: String = stringResource(R.string.picker_empty_no_model),
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel ?: emptyText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(emptyText) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Rounded pill for TTFT / tps. */
@Composable
fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val accent = DroidTheme.extra.accent
    Row(
        modifier = modifier
            .border(BorderStroke(1.dp, accent), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MetricSmallType, color = accent)
    }
}

/**
 * The three control tiers of UI_DESIGN.md §4.4.
 *
 * `PrimaryButton` is the screen's one filled action, `OutlinedToolButton` is everything
 * secondary, and selection is expressed with [ChoiceChipRow] — never with a filled
 * button, which is what used to make every page a row of purple blocks.
 *
 * @param height 52dp by default; dense rows (the chat status line) pass 40dp.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(height),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OutlinedToolButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DroidTheme.extra.surfaceHigh,
        ),
        modifier = modifier.height(height),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The standard content card: a `surfaceContainerHigh` fill plus a 1dp `outlineVariant`
 * hairline.
 *
 * In light theme the fill (#EEEAF8) sits only about 1.09:1 against the background
 * (#F6F5FB), so the card edge was invisible and the boundary had to be guessed. The
 * hairline supplies it in both themes. Padding stays with the caller, since it runs from
 * 10dp for list entries to 20dp for form sections.
 */
@Composable
fun DroidCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/**
 * Shared empty state: an icon, one sentence, and the action that resolves it.
 *
 * The pages used to handle this three different ways — a bare sentence on Chat, an empty
 * area on Models, and a paragraph on Benchmark whose button said「去 Models 页」in English
 * while every other label was Chinese. The action is filled because an empty screen has no
 * competing primary action (UI_DESIGN 4.4).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            PrimaryButton(actionLabel, onClick = onAction)
        }
    }
}

/**
 * Single-line text field for one numeric setting.
 *
 * The typed text lives in a local buffer that is mirrored to the caller on every
 * keystroke. Keeping the buffer is what makes intermediate states such as `0.`
 * survive: feeding the parsed value straight back as the field's text (the previous
 * approach) makes the caret fight the user while they turn `0.7` into `0.75`.
 *
 * Committing per keystroke rather than on blur is deliberate. In touch mode a tap on
 * a button does not move focus, so a blur-triggered commit fires only when the user
 * taps another text field or the IME action — leaving the screen with a pending edit
 * silently dropped it. Text the caller rejects is not stored; it stays on screen
 * while the field is being edited and reverts to [value] once focus leaves.
 *
 * @param value canonical text owned by the caller
 * @param decimal false uses the integer keyboard
 * @param note shown underneath only while [enabled] is false
 */
@Composable
fun NumericField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    decimal: Boolean = false,
    note: String? = null,
) {
    var buffer by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Re-sync from the canonical value only while the field is idle: external updates
    // (defaults reloaded, engine switched) should land, but never mid-edit, and text
    // the caller rejected is dropped here instead of being left on screen.
    LaunchedEffect(value, focused) {
        if (!focused) buffer = value
    }

    Column(modifier.alpha(if (enabled) 1f else 0.45f)) {
        OutlinedTextField(
            value = buffer,
            onValueChange = { text ->
                buffer = text
                // A trailing separator is still parseable (`"0."` is Float 0.0), so
                // committing on a successful parse alone would store a value the user has
                // not finished typing — and dropping `0.7` to `0.` would silently persist
                // 0.0. Hold back that one ambiguous state.
                if (!text.endsWith(".")) onCommit(text)
            },
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        )
        if (!enabled && note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Linear progress + primary/secondary text + optional cancel/pause actions. */
@Composable
fun ProgressHeader(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    paused: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onAbandon: (() -> Unit)? = null,
) {
    val extra = DroidTheme.extra
    val strokeColor = if (paused) extra.warn else Color.Transparent
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (paused) {
                    Modifier.border(BorderStroke(1.dp, strokeColor), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (paused) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.common_paused_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.warn,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) {
                    PrimaryButton(stringResource(R.string.action_resume), onClick = { onResume?.invoke() }, modifier = Modifier.weight(1f))
                    OutlinedToolButton(stringResource(R.string.bench_abandon), onClick = { onAbandon?.invoke() }, modifier = Modifier.weight(1f))
                } else if (onCancel != null) {
                    OutlinedToolButton(stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Height every `ResultTable` cell is pinned to.
 *
 * The pinned first column and the horizontally scrolling pane are two independent
 * `Column`s, so nothing couples their row heights: as soon as one side wraps to a
 * second line the rest of the table is off by one row. A shared fixed height removes
 * the failure mode instead of trying to synchronise it. 52dp fits two lines of
 * labelMedium plus the cell padding.
 */
private val TableRowHeight = 52.dp

/** One header/data cell inside [ResultTable]. */
@Composable
fun TableCell(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isMetric: Boolean = false,
) {
    val color = when {
        isError -> MaterialTheme.colorScheme.error
        isMetric -> DroidTheme.extra.accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text,
        style = if (isMetric) MetricSmallType else MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(min = 56.dp)
            .height(TableRowHeight)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/**
 * Horizontally scrollable result table. First column stays pinned
 * (UI_DESIGN.md §4.2 ResultTable).
 */
@Composable
fun ResultTable(
    headers: List<String>,
    rows: List<List<TableCellModel>>,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            TableCell(headers.firstOrNull().orEmpty(), isMetric = false)
            rows.forEach { row ->
                TableCell(row.firstOrNull()?.text.orEmpty(), isError = row.firstOrNull()?.isError == true)
            }
        }
        Row(
            Modifier
                .horizontalScroll(scroll)
                .weight(1f),
        ) {
            Column {
                Row {
                    headers.drop(1).forEach { h ->
                        TableCell(h, modifier = Modifier.width(72.dp))
                    }
                }
                rows.forEach { row ->
                    Row {
                        row.drop(1).forEach { cell ->
                            TableCell(
                                cell.text,
                                isError = cell.isError,
                                isMetric = cell.isMetric,
                                modifier = Modifier.width(72.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

data class TableCellModel(
    val text: String,
    val isError: Boolean = false,
    val isMetric: Boolean = false,
)

/** Warm-cooling notice / one-line warnings. */
@Composable
fun WarningBanner(
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val warn = DroidTheme.extra.warn
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(warn.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Model size for display, in decimal units.
 *
 * Decimal rather than binary on purpose: model hubs advertise sizes that way, so this
 * number matches what the download page said. Native library sizes in Settings use binary
 * units instead.
 */
fun formatModelSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}

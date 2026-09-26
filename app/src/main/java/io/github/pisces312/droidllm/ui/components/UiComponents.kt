package io.github.pisces312.droidllm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import io.github.pisces312.droidllm.ui.theme.MetricSmallType

/** One row: status dot + engine name + status sentence. */
@Composable
fun EngineStatusCard(
    name: String,
    available: Boolean,
    statusText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val extra = DroidTheme.extra
    val contentAlpha = if (available) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (available) extra.ok else extra.textDisabled),
        )
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

/** Outlined dropdown for model selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emptyText: String = "暂无模型，先到「模型」页添加",
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

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
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
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DroidTheme.extra.surfaceHigh,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
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
                    "已暂停（暂停区间不计时）",
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.warn,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) {
                    PrimaryButton("继续", onClick = { onResume?.invoke() }, modifier = Modifier.weight(1f))
                    OutlinedToolButton("放弃本次评测", onClick = { onAbandon?.invoke() }, modifier = Modifier.weight(1f))
                } else if (onCancel != null) {
                    OutlinedToolButton("取消", onClick = onCancel, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

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
                Text("关闭", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

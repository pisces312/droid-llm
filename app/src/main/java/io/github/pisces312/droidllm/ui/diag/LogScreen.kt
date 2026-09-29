package io.github.pisces312.droidllm.ui.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.common.diag.CrashReporter
import io.github.pisces312.droidllm.common.diag.DiagEntry
import io.github.pisces312.droidllm.common.diag.DiagLevel
import io.github.pisces312.droidllm.common.diag.DiagLogger
import io.github.pisces312.droidllm.common.diag.EngineLogcatCapture
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.ui.components.DroidCard
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LogViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val probe: DeviceProbe,
) : ViewModel() {

    val entries: StateFlow<List<DiagEntry>> = DiagLogger.entries

    private val _enabled = MutableStateFlow(DiagLogger.isEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _engineLog = MutableStateFlow<String?>(null)

    /**
     * Latest native/logcat snapshot, or null before the first capture. Kept
     * detached from [DiagLogger]'s ring buffer -- see [EngineLogcatCapture] for
     * why feeding a capture back would loop.
     */
    val engineLog: StateFlow<String?> = _engineLog.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _crashReport = MutableStateFlow<CrashReportInfo?>(null)

    /** Newest crash report on disk, or null when the app has never crashed. */
    val crashReport: StateFlow<CrashReportInfo?> = _crashReport.asStateFlow()

    private val _crashText = MutableStateFlow<String?>(null)

    /** Body of the crash report being viewed; null closes the dialog. */
    val crashText: StateFlow<String?> = _crashText.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.current()
            DiagLogger.setEnabled(settings.diagnosticLogging)
            _enabled.value = settings.diagnosticLogging
        }
        refreshCrashReport()
    }

    fun refreshCrashReport() {
        viewModelScope.launch(Dispatchers.IO) {
            _crashReport.value = CrashReporter.latestReport()?.let(CrashReportInfo::of)
        }
    }

    fun openCrashReport() {
        viewModelScope.launch(Dispatchers.IO) {
            _crashText.value = CrashReporter.latestReport()
                ?.let { file -> runCatching { file.readText() }.getOrNull() }
                ?: "没有崩溃报告文件"
        }
    }

    fun closeCrashReport() {
        _crashText.value = null
    }

    fun shareCrashReport() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = CrashReporter.latestReport() ?: return@launch fail("没有崩溃报告文件")
            shareFile(file, subject = "DroidLLM crash report", chooserTitle = "分享崩溃报告")
        }
    }

    fun deleteCrashReports() {
        viewModelScope.launch(Dispatchers.IO) {
            CrashReporter.deleteAllReports()
            _crashReport.value = null
        }
    }

    fun setEnabled(value: Boolean) {
        DiagLogger.setEnabled(value)
        _enabled.value = value
        viewModelScope.launch { settingsStore.setDiagnosticLogging(value) }
    }

    fun clear() {
        DiagLogger.clear()
        _engineLog.value = null
        DiagLogger.i(TAG, "log cleared by user")
    }

    /**
     * Dumps this process's logcat, which is where the native engines write. The
     * exec is blocking, so it runs on IO and reports progress through
     * [capturing]; the result replaces any previous snapshot.
     */
    fun captureEngineLogs() {
        if (_capturing.value) return
        _capturing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val text = EngineLogcatCapture.capture()
            _engineLog.value = text
            _capturing.value = false
            if (text == null) {
                DiagLogger.w(TAG, "engine logcat capture produced nothing")
            } else {
                DiagLogger.i(TAG, "engine logcat captured chars=${text.length} lines=${text.count { it == '\n' }}")
            }
            withContext(Dispatchers.Main) {
                toast(if (text == null) "未取到引擎日志，请先复现再试" else "已抓取引擎日志（${text.length / 1024} KB）")
            }
        }
    }

    /**
     * Renders the buffer to `filesDir/logs/` and hands the file to the system
     * chooser. A `.txt` going through [FileProvider] is what makes 微信 / 邮件
     * able to attach it with no storage permission on the user's side.
     *
     * The engine snapshot is attached automatically: requiring a separate
     * "capture" tap is exactly the step that gets skipped right after a bug
     * reproduces. If nothing was captured yet, do it now.
     */
    fun share() {
        viewModelScope.launch(Dispatchers.IO) {
            val engineLog = _engineLog.value
                ?: EngineLogcatCapture.capture()?.also { _engineLog.value = it }
            val file = DiagLogger.flushToFile(header = deviceHeader(), engineLog = engineLog)
                ?: return@launch fail("无法写入日志文件")
            shareFile(file, subject = "DroidLLM log", chooserTitle = "分享日志")
        }
    }

    /** The one place that builds the FileProvider `ACTION_SEND` chooser. */
    private fun shareFile(file: File, subject: String, chooserTitle: String) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return fail("无法生成分享链接")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { fail("没有可用的分享目标") }
    }

    fun copyToClipboard() {
        // Clipboard and Toast are main-thread APIs. Reuse the cached snapshot
        // rather than exec'ing logcat here -- this runs on the main thread.
        val text = DiagLogger.reportText(deviceHeader(), _engineLog.value)
        clipboard().setPrimaryClip(ClipData.newPlainText("DroidLLM log", text))
        toast("日志已复制（${DiagLogger.entries.value.size} 条）")
    }

    /** File path shown on screen so the user can also pull it with adb. */
    fun filePath(): String? = DiagLogger.logFilePath()

    private fun deviceHeader(): List<String> = listOf(
        "app: ${appVersion()}",
        "android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "device: ${Build.MANUFACTURER} ${Build.MODEL}",
        "soc: ${probe.probe().socModel ?: "-"}",
    )

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Posted to the main looper rather than shown inline: every caller here
     * sits inside a `Dispatchers.IO` block, and `Toast`'s constructor throws
     * "Can't toast on a thread that has not called Looper.prepare()" off the
     * main thread. Reaching that path means a failure, so a toast crash would
     * have masked the message the user actually needed.
     */
    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun fail(message: String) = toast(message)

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "$name ($code)"
    }.getOrDefault("?")

    private companion object {
        const val TAG = "log"
        val mainHandler = Handler(Looper.getMainLooper())
    }
}

/** Snapshot of the newest crash report; a raw [File] would recompose on any field read. */
data class CrashReportInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val timeMs: Long,
) {
    companion object {
        fun of(file: File) = CrashReportInfo(
            name = file.name,
            path = file.absolutePath,
            sizeBytes = file.length(),
            timeMs = file.lastModified(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit, vm: LogViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val engineLog by vm.engineLog.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val crashReport by vm.crashReport.collectAsStateWithLifecycle()
    val crashText by vm.crashText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDetail by remember { mutableStateOf(false) }
    var showEngineLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            // The cards alone are ~87% of a phone screen tall, so a plain Column
            // overflowed and squeezed the new card's buttons to zero height
            // (`[0,0][0,0]` in the accessibility dump: rendered, untappable).
            // The cards therefore scroll on their own. `fill = false` caps them
            // at their weight share and hands the rest to the list; 7:1 is what
            // keeps the list at the ~12% it occupied before the card existed,
            // while still letting the control card fit unscrolled in the common
            // (no crash report) case.
            Column(
                Modifier
                    .weight(CARD_WEIGHT, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))

                // Crash first: it is short, it only exists after a crash, and
                // after a crash it is the thing the user actually came for.
                // Placed below the tall control card it would need scrolling
                // into view to become tappable at all.
                crashReport?.let { report ->
                    CrashCard(
                        report = report,
                        onOpen = vm::openCrashReport,
                        onShare = vm::shareCrashReport,
                        onDelete = vm::deleteCrashReports,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                ControlCard(
                    enabled = enabled,
                    count = entries.size,
                    filePath = vm.filePath(),
                    engineLog = engineLog,
                    capturing = capturing,
                    onToggle = vm::setEnabled,
                    onShare = vm::share,
                    onCopy = vm::copyToClipboard,
                    onClear = vm::clear,
                    onOpenDetail = { showDetail = true },
                    onCaptureEngine = vm::captureEngineLogs,
                    onOpenEngineLog = { showEngineLog = true },
                )
            }

            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(LIST_WEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无日志。复现问题后回到这里即可看到记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LogList(entries, Modifier.weight(LIST_WEIGHT))
            }
        }
    }

    if (showDetail) {
        MonospaceTextDialog(
            title = "运行日志（${entries.size} 条）",
            text = renderEntries(entries),
            onDismiss = { showDetail = false },
        )
    }

    if (showEngineLog) {
        MonospaceTextDialog(
            title = "引擎内部日志",
            text = engineLog ?: "尚未抓取。返回上一页点「抓取引擎日志」。",
            onDismiss = { showEngineLog = false },
        )
    }

    crashText?.let { text ->
        MonospaceTextDialog(
            title = "崩溃报告",
            text = text,
            onDismiss = vm::closeCrashReport,
        )
    }
}

@Composable
private fun CrashCard(
    report: CrashReportInfo,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    DroidCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("崩溃报告", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "崩溃时自动写入，含异常堆栈与本进程 logcat。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${crashTimeFormat.format(Date(report.timeMs))} · ${formatSize(report.sizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                report.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedToolButton("查看", onOpen, Modifier.weight(1f), height = 44.dp)
                OutlinedToolButton("分享", onShare, Modifier.weight(1f), height = 44.dp)
                OutlinedToolButton("删除全部", onDelete, Modifier.weight(1f), height = 44.dp)
            }
        }
    }
}

@Composable
private fun ControlCard(
    enabled: Boolean,
    count: Int,
    filePath: String?,
    engineLog: String?,
    capturing: Boolean,
    onToggle: (Boolean) -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onOpenDetail: () -> Unit,
    onCaptureEngine: () -> Unit,
    onOpenEngineLog: () -> Unit,
) {
    DroidCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("诊断日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "记录引擎加载、提示词长度、首 token 延迟、生成 token 数与异常堆栈。" +
                    "捕获最近 2000 条，超出后自动丢弃最旧的记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("开启记录", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = onToggle)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "已捕获 $count 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "复现步骤：开启记录 → 回到聊天页复现 → 返回这里点「分享日志」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filePath != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    filePath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedToolButton("分享日志", onShare, Modifier.weight(1f), enabled = count > 0, height = 44.dp)
                OutlinedToolButton("复制", onCopy, Modifier.weight(1f), enabled = count > 0, height = 44.dp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedToolButton("全屏查看", onOpenDetail, Modifier.weight(1f), enabled = count > 0, height = 44.dp)
                OutlinedToolButton("清空", onClear, Modifier.weight(1f), enabled = count > 0, height = 44.dp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                if (capturing) "抓取中…" else "抓取引擎日志",
                onCaptureEngine,
                Modifier.fillMaxWidth(),
                enabled = !capturing,
                height = 44.dp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "读取本进程 logcat，取到引擎 native 内部输出（MNN / llama.cpp / Genie / LiteRT）。" +
                    "分享时会自动附带最近一次结果。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (engineLog != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "引擎日志已抓取（${engineLog.length / 1024} KB）· 点击查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenEngineLog)
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun LogList(entries: List<DiagEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Follow the tail the way a terminal does: new entries keep the view pinned
    // to the bottom unless the user has scrolled up to read history.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        // Key on [DiagEntry.seq], never on timestamp+text: the four engines emit
        // identical probe messages within the same millisecond, and a duplicate
        // key is a hard crash in LazyColumn.
        items(entries, key = { it.seq }) { entry ->
            LogRow(entry)
        }
    }
}

@Composable
private fun LogRow(entry: DiagEntry) {
    val time = remember(entry.timestampMs) { timeFormat.format(Date(entry.timestampMs)) }
    val color = when (entry.level) {
        DiagLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        DiagLevel.INFO -> MaterialTheme.colorScheme.onSurface
        DiagLevel.WARN -> DroidTheme.extra.warn
        DiagLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    SelectionContainer {
        Text(
            text = "$time ${entry.level.tag}/${entry.tag}: ${entry.message}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Shared by the buffer view and the engine-logcat snapshot. */
@Composable
private fun MonospaceTextDialog(title: String, text: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text(title) },
        text = {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
    )
}

/** One rendered line per entry; the same shape as [DiagLogger]'s own renderer. */
private fun renderEntries(entries: List<DiagEntry>): String =
    entries.joinToString("\n") { entry ->
        "${timeFormat.format(Date(entry.timestampMs))} ${entry.level.tag}/${entry.tag}: ${entry.message}"
    }

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * Height split on the log screen, in `Column` weight units. See the comment at
 * the call site: the cards need almost a whole screen, the list keeps ~1/8.
 */
private const val CARD_WEIGHT = 7f
private const val LIST_WEIGHT = 1f

private val crashTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

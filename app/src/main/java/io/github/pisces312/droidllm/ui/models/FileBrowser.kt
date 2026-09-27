package io.github.pisces312.droidllm.ui.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import java.io.File

object FileBrowserRules {

    fun hasAllFilesAccess(): Boolean =
        Environment.isExternalStorageManager()

    fun isOtherAppAndroidData(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.contains("/Android/data/")) return false
        val marker = "/Android/data/"
        val idx = p.indexOf(marker)
        val rest = p.substring(idx + marker.length)
        val pkg = rest.substringBefore('/')
        return pkg.isNotEmpty() && pkg != "io.github.pisces312.droidllm"
    }

    /** True when the entry cannot be selected for this engine. */
    fun isBlocked(path: String, readable: Boolean): Boolean {
        if (isOtherAppAndroidData(path)) return true
        return !readable
    }

    fun matchesEngine(engineId: EngineId, file: File): Boolean {
        if (file.isDirectory) {
            return when (engineId) {
                EngineId.LITERT -> true
                EngineId.LLAMACPP -> true
                EngineId.MNN, EngineId.GENIE, EngineId.FAKE -> true
            }
        }
        val name = file.name.lowercase()
        return when (engineId) {
            EngineId.LITERT -> name.endsWith(".litertlm") || name.endsWith(".task")
            EngineId.LLAMACPP -> name.endsWith(".gguf")
            EngineId.MNN, EngineId.GENIE -> false
            EngineId.FAKE -> true
        }
    }

    fun engineFilterHint(engineId: EngineId): String = when (engineId) {
        EngineId.LITERT -> "选择 .litertlm / .task 文件或目录"
        EngineId.LLAMACPP -> "选择 .gguf 文件或目录"
        EngineId.MNN -> "选择模型目录（需含 config.json + llm.mnn）"
        EngineId.GENIE -> "选择模型目录（需含 genie_config.json + tokenizer.json + *.bin）"
        EngineId.FAKE -> "任意路径"
    }

    fun rootDirs(context: Context): List<File> {
        val appFiles = context.getExternalFilesDir(null)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val storage = File("/storage/emulated/0")
        val list = mutableListOf<File>()
        appFiles?.let { list += it }
        list += downloads
        if (hasAllFilesAccess() && storage.isDirectory) list += storage
        return list.distinctBy { it.absolutePath }
    }

    /**
     * Open the system "All files access" page. The bare ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
     * intent is a no-op on some devices; always attach the package URI and fall back.
     */
    fun openAllFilesAccessSettings(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in candidates) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) return
        }
    }
}

/**
 * Built-in path browser (UI_DESIGN §5.2 / DESIGN §1.3).
 * java.io.File semantics; MANAGE_EXTERNAL_STORAGE for broad access.
 * Unauthorized → app-private only + guide. Other apps' Android/data/ greyed out.
 */
@Composable
fun FileBrowserDialog(
    engineId: EngineId,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
    startDir: File? = null,
) {
    val context = LocalContext.current
    var current by remember {
        val preferred = startDir?.takeIf { it.isDirectory }
        mutableStateOf(preferred ?: FileBrowserRules.rootDirs(context).first())
    }
    var entries by remember { mutableStateOf(emptyList<FileEntry>()) }
    var authorized by remember { mutableStateOf(FileBrowserRules.hasAllFilesAccess()) }

    LaunchedEffect(current) {
        entries = listEntries(current, engineId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text("选择模型路径") },
        text = {
            Column(Modifier.fillMaxWidth().height(420.dp)) {
                Text(
                    FileBrowserRules.engineFilterHint(engineId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
                if (!authorized) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "未授予「所有文件访问」权限，仅可浏览 App 私有目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = DroidTheme.extra.warn,
                    )
                    TextButton(onClick = {
                        FileBrowserRules.openAllFilesAccessSettings(context)
                    }) { Text("去系统设置授权") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(entries) { entry ->
                        val blocked = entry.blocked
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (blocked) 0.45f else 1f)
                                .clickable(enabled = !blocked && entry.selectable) {
                                    if (entry.file.isDirectory) {
                                        current = entry.file
                                    } else {
                                        onPick(entry.file)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (blocked) DroidTheme.extra.textDisabled
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (entry.hint != null) {
                                Text(
                                    entry.hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DroidTheme.extra.textDisabled,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val parent = current.parentFile
                        if (parent != null) current = parent
                    }) { Text("上级") }
                    if (FileBrowserRules.matchesEngine(engineId, current) && !FileBrowserRules.isBlocked(current.absolutePath, current.canRead())) {
                        PrimaryButton(
                            text = "选此目录",
                            onClick = { onPick(current) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private data class FileEntry(
    val file: File,
    val label: String,
    val selectable: Boolean,
    val blocked: Boolean,
    val hint: String?,
)

private fun listEntries(dir: File, engineId: EngineId): List<FileEntry> {
    val children = dir.listFiles()?.toList().orEmpty()
    return children
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .map { f ->
            val readable = f.canRead()
            val blocked = FileBrowserRules.isBlocked(f.absolutePath, readable)
            val matches = FileBrowserRules.matchesEngine(engineId, f)
            val hint = when {
                blocked && FileBrowserRules.isOtherAppAndroidData(f.absolutePath) ->
                    "其他 App 的 Android/data/ 不可访问"
                !readable -> "无读取权限"
                !matches && f.isFile -> "扩展名不匹配"
                f.isDirectory -> "目录"
                else -> null
            }
            FileEntry(
                file = f,
                label = f.name + if (f.isDirectory) "/" else "",
                selectable = matches && readable && !blocked,
                blocked = blocked,
                hint = hint,
            )
        }
}

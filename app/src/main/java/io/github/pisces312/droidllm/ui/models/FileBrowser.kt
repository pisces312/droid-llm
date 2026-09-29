package io.github.pisces312.droidllm.ui.models

import androidx.compose.ui.res.stringResource
import io.github.pisces312.droidllm.R

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.sp
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import java.io.File

object FileBrowserRules {

    fun hasAllFilesAccess(): Boolean =
        Environment.isExternalStorageManager()

    /**
     * @param ownPackage the running package name. Debug builds ship as
     *   `<app>.debug`, so the own tree must be matched by the live package
     *   instead of a hardcoded release id — otherwise the app greys out its
     *   own external files dir.
     */
    fun isOtherAppAndroidData(path: String, ownPackage: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.contains("/Android/data/")) return false
        val marker = "/Android/data/"
        val idx = p.indexOf(marker)
        val rest = p.substring(idx + marker.length)
        val pkg = rest.substringBefore('/')
        return pkg.isNotEmpty() && pkg != ownPackage
    }

    /** True when the entry cannot be selected for this engine. */
    fun isBlocked(path: String, readable: Boolean, ownPackage: String): Boolean {
        if (isOtherAppAndroidData(path, ownPackage)) return true
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

    fun engineFilterHint(context: Context, engineId: EngineId): String = when (engineId) {
        EngineId.LITERT -> context.getString(R.string.browser_hint_litert)
        EngineId.LLAMACPP -> context.getString(R.string.browser_hint_llamacpp)
        EngineId.MNN -> context.getString(R.string.browser_hint_mnn)
        EngineId.GENIE -> context.getString(R.string.browser_hint_genie)
        EngineId.FAKE -> context.getString(R.string.browser_hint_any)
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
 *
 * @param startDir first directory to open; falls back to [FileBrowserRules.rootDirs]
 *   when missing. Callers should pass the configured model root so registration
 *   browsing starts where the user keeps models.
 * @param modelRoot when set and readable, shows a shortcut that jumps back here.
 */
@Composable
fun FileBrowserDialog(
    engineId: EngineId,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
    startDir: File? = null,
    modelRoot: File? = null,
) {
    val context = LocalContext.current
    var current by remember {
        val preferred = startDir?.takeIf { it.isDirectory }
        mutableStateOf(preferred ?: FileBrowserRules.rootDirs(context).first())
    }
    var entries by remember { mutableStateOf(emptyList<FileEntry>()) }
    var authorized by remember { mutableStateOf(FileBrowserRules.hasAllFilesAccess()) }

    LaunchedEffect(current) {
        entries = listEntries(context, current, engineId, context.packageName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.browser_title)) },
        text = {
            Column(Modifier.fillMaxWidth().height(420.dp)) {
                Text(
                    FileBrowserRules.engineFilterHint(context, engineId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                if (!authorized) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.browser_no_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = DroidTheme.extra.warn,
                    )
                    TextButton(onClick = {
                        FileBrowserRules.openAllFilesAccessSettings(context)
                    }) { Text(stringResource(R.string.browser_grant_permission)) }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(entries) { entry ->
                        val blocked = entry.blocked
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // 44dp is the minimum comfortable touch target; a bare
                                // 14sp line with 8dp padding came to about 36dp.
                                .heightIn(min = 44.dp)
                                .alpha(if (blocked) 0.45f else 1f)
                                .clickable(enabled = !blocked && entry.selectable) {
                                    if (entry.file.isDirectory) {
                                        current = entry.file
                                    } else {
                                        onPick(entry.file)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                color = if (blocked) DroidTheme.extra.textDisabled
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (entry.hint != null) {
                                Text(
                                    entry.hint,
                                    style = MaterialTheme.typography.bodySmall,
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
                    }) { Text(stringResource(R.string.browser_parent)) }
                    val shortcutRoot = modelRoot?.takeIf { it.isDirectory && it.canRead() }
                    if (shortcutRoot != null && shortcutRoot.absolutePath != current.absolutePath) {
                        TextButton(onClick = { current = shortcutRoot }) { Text(stringResource(R.string.common_model_root)) }
                    }
                    if (FileBrowserRules.matchesEngine(engineId, current) &&
                        !FileBrowserRules.isBlocked(current.absolutePath, current.canRead(), context.packageName)
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.browser_pick_dir),
                            onClick = { onPick(current) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
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

private fun listEntries(
    context: Context,
    dir: File,
    engineId: EngineId,
    ownPackage: String,
): List<FileEntry> {
    val children = dir.listFiles()?.toList().orEmpty()
    return children
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .map { f ->
            val readable = f.canRead()
            val blocked = FileBrowserRules.isBlocked(f.absolutePath, readable, ownPackage)
            val matches = FileBrowserRules.matchesEngine(engineId, f)
            val hint = when {
                blocked && FileBrowserRules.isOtherAppAndroidData(f.absolutePath, ownPackage) ->
                    context.getString(R.string.browser_hint_other_app)
                !readable -> context.getString(R.string.browser_hint_no_read)
                !matches && f.isFile -> context.getString(R.string.browser_hint_ext_mismatch)
                f.isDirectory -> context.getString(R.string.browser_hint_dir)
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

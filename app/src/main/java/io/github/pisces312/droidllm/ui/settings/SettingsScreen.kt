package io.github.pisces312.droidllm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pisces312.droidllm.common.bench.BenchmarkDao
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.common.settings.AppSettings
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.ThemeMode
import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.ProbeContext
import io.github.pisces312.droidllm.ui.components.OutlinedToolButton
import io.github.pisces312.droidllm.ui.components.PrimaryButton
import io.github.pisces312.droidllm.ui.theme.DroidTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceProbe: DeviceProbe,
    private val settingsStore: AppSettingsStore,
    private val benchmarkDao: BenchmarkDao,
) : ViewModel() {

    private val _probe = MutableStateFlow<ProbeContext?>(null)
    val probe: StateFlow<ProbeContext?> = _probe.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    init {
        _probe.value = deviceProbe.probe()
    }

    fun modelRoot(): String = deviceProbe.defaultModelRoot().absolutePath

    fun benchmarkDir(): String = deviceProbe.defaultModelRoot().parentFile
        ?.resolve("benchmark")?.absolutePath
        ?: (deviceProbe.defaultModelRoot().absolutePath + "/../benchmark")

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    fun setMultiResidency(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMultiModelResidency(enabled) }
    }

    fun setSampling(
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        threads: Int?,
        maxNewTokens: Int?,
        backend: Backend?,
    ) {
        val cur = settings.value
        viewModelScope.launch {
            settingsStore.setSampling(
                temperature = temperature ?: cur.temperature,
                topK = topK ?: cur.topK,
                topP = topP ?: cur.topP,
                threads = threads ?: cur.threads,
                maxNewTokens = maxNewTokens ?: cur.maxNewTokens,
                backend = backend ?: cur.backend,
            )
        }
    }

    fun clearBenchDb() {
        viewModelScope.launch {
            benchmarkDao.clear()
            _message.value = "已清空 benchmark 库"
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val probe by vm.probe.collectAsState()
    val settings by vm.settings.collectAsState()
    val message by vm.message.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        SectionCard("外观") {
            ThemeModeRow(
                selected = settings.themeMode,
                onSelect = vm::setThemeMode,
            )
        }

        SectionCard("默认采样参数") {
            Text(
                "聊天页采样面板的默认值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.temperature.toString(),
                    onValueChange = { v ->
                        v.toFloatOrNull()?.let { vm.setSampling(temperature = it, topK = null, topP = null, threads = null, maxNewTokens = null, backend = null) }
                    },
                    label = { Text("temp") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = settings.topK.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.setSampling(temperature = null, topK = it, topP = null, threads = null, maxNewTokens = null, backend = null) }
                    },
                    label = { Text("top_k") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.topP.toString(),
                    onValueChange = { v ->
                        v.toFloatOrNull()?.let { vm.setSampling(temperature = null, topK = null, topP = it, threads = null, maxNewTokens = null, backend = null) }
                    },
                    label = { Text("top_p") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = settings.threads.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.setSampling(temperature = null, topK = null, topP = null, threads = it, maxNewTokens = null, backend = null) }
                    },
                    label = { Text("threads") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settings.maxNewTokens.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { vm.setSampling(temperature = null, topK = null, topP = null, threads = null, maxNewTokens = it, backend = null) }
                    },
                    label = { Text("maxNewTokens") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = settings.backend.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("backend") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard("内存") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("多模型驻留", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "开启后切换引擎/模型不自动 unload；默认关闭（单模型驻留）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.multiModelResidency,
                    onCheckedChange = vm::setMultiResidency,
                )
            }
            if (settings.multiModelResidency) {
                Text(
                    "8GB 机型易 OOM，建议保持关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = DroidTheme.extra.warn,
                )
            }
        }

        SectionCard("数据") {
            Text("模型根目录", style = MaterialTheme.typography.labelSmall)
            Text(vm.modelRoot(), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("评测导出目录", style = MaterialTheme.typography.labelSmall)
            Text(
                vm.benchmarkDir() + "/bench_*.json",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedToolButton(
                "清空 benchmark 库",
                onClick = vm::clearBenchDb,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("设备信息") {
            probe?.let { p ->
                Text("SoC: ${p.socModel ?: "unknown"}")
                Text("SDK: ${p.sdkInt}")
                Text("OpenCL: ${p.hasOpenCl}  cdsp: ${p.hasCdspRpc}")
                Text("RAM: ${p.totalRamMb} MB")
                Text("可用存储: ${p.availableStorageMb} MB")
            }
        }

        SectionCard("关于") {
            Text(
                "droid-llm：同一台真机上四引擎（LiteRT-LM / MNN / Genie / llama.cpp）实测对比。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "依赖与许可：LiteRT-LM (Apache-2.0)、MNN (Apache-2.0)、QAIRT/Genie (Qualcomm)、" +
                    "llama.cpp (MIT)、Jetpack Compose / Hilt / Room / DataStore (Apache-2.0)。" +
                    "详见 docs/ENGINE_INTEGRATION.md。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            val label = when (mode) {
                ThemeMode.DARK -> "深色"
                ThemeMode.LIGHT -> "浅色"
                ThemeMode.SYSTEM -> "跟随系统"
            }
            if (mode == selected) {
                PrimaryButton(label, onClick = { onSelect(mode) }, modifier = Modifier.weight(1f))
            } else {
                OutlinedToolButton(label, onClick = { onSelect(mode) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

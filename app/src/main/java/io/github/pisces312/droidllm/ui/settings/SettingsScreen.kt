package io.github.pisces312.droidllm.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.engineapi.ProbeContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceProbe: DeviceProbe,
) : ViewModel() {
    private val _probe = MutableStateFlow<ProbeContext?>(null)
    val probe: StateFlow<ProbeContext?> = _probe.asStateFlow()

    init {
        _probe.value = deviceProbe.probe()
    }

    fun modelRoot(): String = deviceProbe.defaultModelRoot().absolutePath
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val probe by vm.probe.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("默认采样参数 / 后端偏好 / 线程数将在后续阶段接入 DataStore。", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("设备信息", style = MaterialTheme.typography.titleMedium)
        probe?.let { p ->
            Text("SoC: ${p.socModel ?: "unknown"}")
            Text("SDK: ${p.sdkInt}")
            Text("OpenCL: ${p.hasOpenCl}  cdsp: ${p.hasCdspRpc}")
            Text("RAM: ${p.totalRamMb} MB")
            Text("可用存储: ${p.availableStorageMb} MB")
        }
        Spacer(Modifier.height(12.dp))
        Text("模型根目录", style = MaterialTheme.typography.titleMedium)
        Text(vm.modelRoot(), style = MaterialTheme.typography.bodySmall)
    }
}

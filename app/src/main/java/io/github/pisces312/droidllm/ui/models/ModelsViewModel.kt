package io.github.pisces312.droidllm.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pisces312.droidllm.common.model.FileFormatValidator
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.model.ValidationResult
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelStore: ModelPathStore,
) : ViewModel() {

    val models: StateFlow<List<LocalModel>> = modelStore.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _pendingPath = MutableStateFlow("")
    val pendingPath: StateFlow<String> = _pendingPath.asStateFlow()

    fun setPendingPath(path: String) {
        _pendingPath.value = path
    }

    fun add(engineId: EngineId, displayName: String, path: String) {
        if (displayName.isEmpty() || path.isEmpty()) {
            _message.value = "显示名和路径不能为空"
            return
        }
        val file = File(path)
        if (!file.exists()) {
            _message.value = "路径不存在：$path；检查是否已授权存储或路径拼写"
            return
        }
        val location = ModelLocation.FilePath(path)
        val result = FileFormatValidator.validatePath(engineId, file)
        if (result is ValidationResult.Failed) {
            _message.value = "格式校验失败：${result.reason}"
            return
        }
        viewModelScope.launch {
            modelStore.upsert(
                LocalModel(
                    id = UUID.randomUUID().toString(),
                    engineId = engineId,
                    displayName = displayName,
                    location = location,
                    formatHint = formatHint(engineId),
                    fileSizeBytes = if (file.isFile) file.length() else null,
                ),
            )
            _message.value = "已添加：$displayName"
            _pendingPath.value = ""
        }
    }

    fun validate(modelId: String) {
        viewModelScope.launch {
            val model = models.value.firstOrNull { it.id == modelId } ?: return@launch
            _message.value = when (val r = FileFormatValidator.validate(model.engineId, model.location)) {
                is ValidationResult.Ok -> "校验通过：${model.displayName}"
                is ValidationResult.Failed -> "校验失败：${r.reason}"
                ValidationResult.Unknown -> "无法校验（SAF/私有路径）：${model.displayName}"
            }
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch {
            modelStore.delete(modelId)
            _message.value = "已删除"
        }
    }

    private fun formatHint(engineId: EngineId): String = when (engineId) {
        EngineId.LITERT -> "litertlm"
        EngineId.MNN -> "mnn_dir"
        EngineId.GENIE -> "genie_dir"
        EngineId.LLAMACPP -> "gguf"
        EngineId.FAKE -> "fake"
    }
}

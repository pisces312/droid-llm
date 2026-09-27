package io.github.pisces312.droidllm.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.common.model.FileFormatValidator
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.model.ValidationResult
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.data.catalog.CatalogModel
import io.github.pisces312.droidllm.data.catalog.DownloadState
import io.github.pisces312.droidllm.data.catalog.ModelCatalog
import io.github.pisces312.droidllm.data.catalog.ModelCatalogLoader
import io.github.pisces312.droidllm.data.catalog.ModelDownloader
import io.github.pisces312.droidllm.data.catalog.ModelSource
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

data class CatalogRow(
    val model: CatalogModel,
    val downloaded: Boolean,
    val downloadState: DownloadState,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelStore: ModelPathStore,
    private val settingsStore: AppSettingsStore,
    private val deviceProbe: DeviceProbe,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val downloader = ModelDownloader()

    val models: StateFlow<List<LocalModel>> = modelStore.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _pendingPath = MutableStateFlow("")
    val pendingPath: StateFlow<String> = _pendingPath.asStateFlow()

    private val _source = MutableStateFlow(ModelSource.HuggingFace)
    val source: StateFlow<ModelSource> = _source.asStateFlow()

    private val _catalog = MutableStateFlow(ModelCatalog())
    val catalog: StateFlow<ModelCatalog> = _catalog.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloader.states

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    /** Shared model root for every engine (configured or default). */
    fun modelRoot(): String =
        cachedRoot ?: deviceProbe.defaultModelRoot().absolutePath

    @Volatile
    private var cachedRoot: String? = null

    fun engineDir(engineId: EngineId): String =
        File(modelRoot(), engineId.name.lowercase()).absolutePath

    init {
        viewModelScope.launch {
            settingsStore.observe().collect { s ->
                cachedRoot = s.modelRootPath
                refreshDownloaded()
            }
        }
        _catalog.value = runCatching { ModelCatalogLoader.load(context) }
            .getOrElse { ModelCatalog() }
        refreshDownloaded()
    }

    fun setSource(source: ModelSource) {
        _source.value = source
    }

    fun refreshDownloaded() {
        val root = File(modelRoot())
        val ids = _catalog.value.models.filter { isDownloaded(it, root) }.map { it.id }.toSet()
        _downloadedIds.value = ids
    }

    fun isDownloaded(model: CatalogModel, root: File = File(modelRoot())): Boolean {
        val engine = model.engine.lowercase()
        val target = File(File(root, engine), model.localPath)
        return if (model.kind == "repo") {
            val marker = model.markerFile ?: "config.json"
            File(target, marker).exists() || target.isDirectory && (target.listFiles()?.isNotEmpty() == true)
        } else {
            target.isFile && target.length() > 0
        }
    }

    fun catalogRows(): List<CatalogRow> {
        val downloaded = _downloadedIds.value
        val states = downloader.states.value
        return _catalog.value.models.map { m ->
            CatalogRow(
                model = m,
                downloaded = m.id in downloaded,
                downloadState = states[m.id] ?: DownloadState(),
            )
        }
    }

    fun download(model: CatalogModel) {
        val source = _source.value
        if (model.repoPath(source) == null) {
            _message.value = "「${model.name}」暂无 ${source.displayName} 源，请切换服务器"
            return
        }
        val engine = model.engine.lowercase()
        val target = File(modelRoot(), engine)
        target.mkdirs()
        viewModelScope.launch {
            val result = downloader.download(model, source, target)
            result.onSuccess {
                refreshDownloaded()
                _message.value = "已下载：${model.name} → ${it.absolutePath}"
            }.onFailure {
                _message.value = "下载失败：${it.message}"
            }
        }
    }

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

    fun registerDownloaded(model: CatalogModel) {
        val file = File(File(modelRoot(), model.engine.lowercase()), model.localPath)
        if (!file.exists()) {
            _message.value = "本地不存在：${file.absolutePath}"
            return
        }
        add(
            engineId = EngineId.entries.firstOrNull { it.name.equals(model.engine, true) } ?: EngineId.LLAMACPP,
            displayName = model.name,
            path = file.absolutePath,
        )
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

    fun update(modelId: String, displayName: String, path: String) {
        val name = displayName.trim()
        val newPath = path.trim()
        if (name.isEmpty() || newPath.isEmpty()) {
            _message.value = "显示名和路径不能为空"
            return
        }
        val current = models.value.firstOrNull { it.id == modelId }
        if (current == null) {
            _message.value = "模型不存在"
            return
        }
        val file = File(newPath)
        if (!file.exists()) {
            _message.value = "路径不存在：$newPath；检查是否已授权存储或路径拼写"
            return
        }
        val result = FileFormatValidator.validatePath(current.engineId, file)
        if (result is ValidationResult.Failed) {
            _message.value = "格式校验失败：${result.reason}"
            return
        }
        viewModelScope.launch {
            modelStore.upsert(
                current.copy(
                    displayName = name,
                    location = ModelLocation.FilePath(newPath),
                    fileSizeBytes = if (file.isFile) file.length() else current.fileSizeBytes,
                ),
            )
            _message.value = "已更新：$name"
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

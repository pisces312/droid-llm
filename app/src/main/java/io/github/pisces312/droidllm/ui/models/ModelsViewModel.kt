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
import io.github.pisces312.droidllm.data.catalog.findModelDir
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
    /** Absolute path when already present on disk (any recognized layout). */
    val localPath: String?,
)

enum class DownloadFilter { ALL, DOWNLOADED, NOT_DOWNLOADED }

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

    private val _downloadFilter = MutableStateFlow(DownloadFilter.ALL)
    val downloadFilter: StateFlow<DownloadFilter> = _downloadFilter.asStateFlow()

    private val _catalog = MutableStateFlow(ModelCatalog())
    val catalog: StateFlow<ModelCatalog> = _catalog.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloader.states

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    /** Catalog id → absolute on-disk path, computed off the main thread. */
    private val _localPaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val localPaths: StateFlow<Map<String, String>> = _localPaths.asStateFlow()

    private val _root = MutableStateFlow("")

    /** Shared model root for every engine (configured or default). */
    val root: StateFlow<String> = _root.asStateFlow()

    fun modelRoot(): String =
        _root.value.ifEmpty { deviceProbe.defaultModelRoot().absolutePath }

    fun engineDir(engineId: EngineId): String =
        File(modelRoot(), engineId.name.lowercase()).absolutePath

    init {
        viewModelScope.launch {
            settingsStore.observe().collect { s ->
                _root.value = s.modelRootPath ?: deviceProbe.defaultModelRoot().absolutePath
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

    fun setDownloadFilter(filter: DownloadFilter) {
        _downloadFilter.value = filter
    }

    fun refreshDownloaded() {
        val rootDir = File(modelRoot())
        val catalogModels = _catalog.value.models
        viewModelScope.launch(Dispatchers.IO) {
            val found = HashMap<String, String>(catalogModels.size)
            catalogModels.forEach { m ->
                findModelDir(m, rootDir)?.let { found[m.id] = it.absolutePath }
            }
            _downloadedIds.value = found.keys
            _localPaths.value = found
        }
    }

    fun catalogRows(filter: DownloadFilter = _downloadFilter.value): List<CatalogRow> {
        val downloaded = _downloadedIds.value
        val paths = _localPaths.value
        val states = downloader.states.value
        return _catalog.value.models.mapNotNull { m ->
            val isDl = m.id in downloaded
            if (filter == DownloadFilter.DOWNLOADED && !isDl) return@mapNotNull null
            if (filter == DownloadFilter.NOT_DOWNLOADED && isDl) return@mapNotNull null
            CatalogRow(
                model = m,
                downloaded = isDl,
                downloadState = states[m.id] ?: DownloadState(),
                localPath = paths[m.id],
            )
        }
    }

    fun download(model: CatalogModel) {
        val source = _source.value
        if (model.repoPath(source) == null) {
            val family = if (source.isHuggingFace) "HuggingFace" else "ModelScope"
            _message.value = "「${model.name}」暂无 $family 源，请切换服务器"
            return
        }
        val root = File(modelRoot())
        root.mkdirs()
        viewModelScope.launch {
            val result = downloader.download(model, source, root)
            result.onSuccess { out ->
                refreshDownloaded()
                val chatable = model.tags.none { it == "ImageGen" || it == "AudioGen" }
                if (chatable) {
                    registerDownloaded(model)
                    _message.value = "已下载并加入模型列表：${model.name}\n$out"
                } else {
                    _message.value = "已下载：${model.name}（非对话模型，未自动加入列表）\n$out"
                }
            }.onFailure {
                _message.value = "下载失败：${it.message}"
            }
        }
    }

    fun setPendingPath(path: String) {
        _pendingPath.value = path
    }

    /** Expected layout under the model root, used as UI hint and import target. */
    fun engineFormatHint(engineId: EngineId): String = when (engineId) {
        EngineId.LITERT -> "单文件 *.task / *.litertlm → {根}/litert/"
        EngineId.MNN -> "模型目录（config.json + *.mnn）→ {根}/mnn/"
        EngineId.GENIE -> "模型目录（genie_config.json + *.bin + tokenizer.json）→ {根}/genie/"
        EngineId.LLAMACPP -> "单文件 *.gguf → {根}/llamacpp/"
        EngineId.FAKE -> "任意"
    }

    /**
     * Copy [sourcePath] into `<modelRoot>/<engine>/<name>` then register it.
     * Never overwrites an existing target (same contract as model-root migration).
     */
    fun importToModelRoot(engineId: EngineId, displayName: String, sourcePath: String) {
        val name = displayName.trim()
        val src = File(sourcePath.trim())
        if (name.isEmpty()) {
            _message.value = "显示名不能为空"
            return
        }
        if (!src.exists()) {
            _message.value = "路径不存在：$sourcePath"
            return
        }
        val validation = FileFormatValidator.validatePath(engineId, src)
        if (validation is ValidationResult.Failed) {
            _message.value = "格式校验失败：${validation.reason}"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val engineDir = File(engineDir(engineId))
            engineDir.mkdirs()
            val dest = File(engineDir, src.name)
            val finalPath = try {
                when {
                    src.canonicalPath == dest.canonicalPath -> src.absolutePath
                    dest.exists() -> {
                        val ok = FileFormatValidator.validatePath(engineId, dest)
                        if (ok is ValidationResult.Ok || ok is ValidationResult.Unknown) {
                            dest.absolutePath
                        } else {
                            _message.value = "目标已存在且无效，未覆盖：${dest.absolutePath}"
                            return@launch
                        }
                    }
                    else -> {
                        if (src.isDirectory) src.copyRecursively(dest, overwrite = false)
                        else src.copyTo(dest, overwrite = false)
                        dest.absolutePath
                    }
                }
            } catch (e: Exception) {
                _message.value = "导入失败：${e.message}"
                return@launch
            }
            val finalFile = File(finalPath)
            val result = FileFormatValidator.validatePath(engineId, finalFile)
            if (result is ValidationResult.Failed) {
                _message.value = "导入后校验失败：${result.reason}"
                return@launch
            }
            modelStore.upsert(
                LocalModel(
                    id = UUID.randomUUID().toString(),
                    engineId = engineId,
                    displayName = name,
                    location = ModelLocation.FilePath(finalPath),
                    formatHint = formatHint(engineId),
                    fileSizeBytes = if (finalFile.isFile) finalFile.length() else null,
                ),
            )
            _pendingPath.value = finalPath
            _message.value = "已导入：$name → $finalPath"
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            val file = _localPaths.value[model.id]?.let(::File)?.takeIf { it.exists() }
                ?: findModelDir(model, File(modelRoot()))
            if (file == null || !file.exists()) {
                _message.value = "本地不存在：${model.name}"
                return@launch
            }
            val path = file.absolutePath
            val existing = models.value.firstOrNull { m ->
                (m.location as? ModelLocation.FilePath)?.path == path
            }
            if (existing != null) {
                _message.value = "已在模型列表中：${existing.displayName}"
                return@launch
            }
            add(
                engineId = EngineId.entries.firstOrNull { it.name.equals(model.engine, true) } ?: EngineId.LLAMACPP,
                displayName = model.name,
                path = path,
            )
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

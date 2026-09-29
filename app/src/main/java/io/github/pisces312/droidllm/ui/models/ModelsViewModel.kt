package io.github.pisces312.droidllm.ui.models

import io.github.pisces312.droidllm.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import io.github.pisces312.droidllm.common.device.DeviceProbe
import io.github.pisces312.droidllm.common.model.FileFormatValidator
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.model.ModelRootMigrator
import io.github.pisces312.droidllm.common.model.ValidationResult
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.data.catalog.CatalogModel
import io.github.pisces312.droidllm.data.catalog.DownloadState
import io.github.pisces312.droidllm.data.catalog.HfHost
import io.github.pisces312.droidllm.data.catalog.ModelAutoImporter
import io.github.pisces312.droidllm.data.catalog.ModelCatalog
import io.github.pisces312.droidllm.data.catalog.ModelCatalogLoader
import io.github.pisces312.droidllm.data.catalog.ModelDownloader
import io.github.pisces312.droidllm.data.catalog.ModelSource
import io.github.pisces312.droidllm.data.catalog.engineFormatTag
import io.github.pisces312.droidllm.data.catalog.findModelDir
import io.github.pisces312.droidllm.data.catalog.matchImported
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.engineIdFromStorage
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
import kotlinx.coroutines.withContext

data class CatalogRow(
    val model: CatalogModel,
    val downloaded: Boolean,
    val downloadState: DownloadState,
    /** Absolute path when already present on disk (any recognized layout). */
    val localPath: String?,
)

/** What one root scan changed; non-null prompts the summary dialog. */
data class ScanChanges(
    val added: List<String>,
    val removed: List<String>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

enum class DownloadFilter { ALL, DOWNLOADED, NOT_DOWNLOADED }

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelStore: ModelPathStore,
    private val settingsStore: AppSettingsStore,
    private val deviceProbe: DeviceProbe,
    private val autoImporter: ModelAutoImporter,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val downloader = ModelDownloader(context)

    val models: StateFlow<List<LocalModel>> = modelStore.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /**
     * Whether [message] is a failure rather than a confirmation.
     *
     * Replaces the old `message.startsWith("校验失败")` test in the UI, which
     * silently stopped matching the moment the messages became translatable.
     */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    /** The one writer: every banner on this page goes through it. */
    private fun show(text: String, isError: Boolean = false) {
        _message.value = text
        _messageIsError.value = isError
    }

    /** Set after a scan that added or removed registrations; cleared on dismiss. */
    private val _scanChanges = MutableStateFlow<ScanChanges?>(null)
    val scanChanges: StateFlow<ScanChanges?> = _scanChanges.asStateFlow()

    private val _pendingPath = MutableStateFlow("")
    val pendingPath: StateFlow<String> = _pendingPath.asStateFlow()

    private val _source = MutableStateFlow(ModelSource.HuggingFace)
    val source: StateFlow<ModelSource> = _source.asStateFlow()

    private val _downloadFilter = MutableStateFlow(DownloadFilter.ALL)
    val downloadFilter: StateFlow<DownloadFilter> = _downloadFilter.asStateFlow()

    /** `null` = all engines; narrows the market catalog rows. */
    private val _marketEngineFilter = MutableStateFlow<EngineId?>(null)
    val marketEngineFilter: StateFlow<EngineId?> = _marketEngineFilter.asStateFlow()

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

    /** HF host resolved from Settings (`hfUseMirror`), default = mirror. */
    private val _hfHost = MutableStateFlow(HfHost.MIRROR)
    val hfHost: StateFlow<String> = _hfHost.asStateFlow()

    fun modelRoot(): String =
        _root.value.ifEmpty { deviceProbe.defaultModelRoot().absolutePath }

    fun engineDir(engineId: EngineId): String =
        File(modelRoot(), engineId.name.lowercase()).absolutePath

    init {
        viewModelScope.launch {
            settingsStore.observe().collect { s ->
                _root.value = s.modelRootPath ?: deviceProbe.defaultModelRoot().absolutePath
                _hfHost.value = HfHost.resolve(s.hfUseMirror)
                refreshDownloaded()
            }
        }
        _catalog.value = runCatching { ModelCatalogLoader.load(context) }
            .getOrElse { ModelCatalog() }
    }

    fun setSource(source: ModelSource) {
        _source.value = source
    }

    fun setDownloadFilter(filter: DownloadFilter) {
        _downloadFilter.value = filter
    }

    fun setMarketEngineFilter(engine: EngineId?) {
        _marketEngineFilter.value = engine
    }

    /**
     * Rescan the model root: register newly found catalog models and drop
     * registrations whose files are gone. When anything changed, [scanChanges]
     * is set so the UI can raise a summary dialog.
     *
     * @param quiet suppress the "nothing changed" banner; the dialog still
     *   appears whenever there were additions or removals.
     */
    fun syncFoundModels(quiet: Boolean = false) {
        val rootDir = File(modelRoot())
        refreshDownloaded()
        viewModelScope.launch(Dispatchers.IO) {
            val removed = autoImporter.pruneMissing()
            val added = autoImporter.registerFound(rootDir)
            val changes = ScanChanges(added = added, removed = removed)
            if (!changes.isEmpty) {
                _scanChanges.value = changes
                _message.value = context.getString(R.string.models_scan_done, added.size, removed.size)
            } else if (!quiet) {
                _message.value = context.getString(R.string.models_scan_no_change)
            }
        }
    }

    fun dismissScanChanges() {
        _scanChanges.value = null
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

    fun catalogRows(
        filter: DownloadFilter = _downloadFilter.value,
        engineFilter: EngineId? = _marketEngineFilter.value,
    ): List<CatalogRow> {
        val downloaded = _downloadedIds.value
        val paths = _localPaths.value
        val states = downloader.states.value
        return _catalog.value.models.mapNotNull { m ->
            val isDl = m.id in downloaded
            if (filter == DownloadFilter.DOWNLOADED && !isDl) return@mapNotNull null
            if (filter == DownloadFilter.NOT_DOWNLOADED && isDl) return@mapNotNull null
            if (engineFilter != null && engineIdFromStorage(m.engine) != engineFilter) {
                return@mapNotNull null
            }
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
            show(context.getString(R.string.models_no_source, model.name, family), isError = true)
            return
        }
        val hfHost = _hfHost.value
        val root = File(modelRoot())
        root.mkdirs()
        viewModelScope.launch {
            val result = downloader.download(model, source, root, hfHost)
            result.onSuccess { out ->
                refreshDownloaded()
                val chatable = model.tags.none { it == "ImageGen" || it == "AudioGen" }
                if (chatable) {
                    registerDownloaded(model)
                    _message.value = context.getString(R.string.models_download_registered, model.name, out)
                } else {
                    _message.value = context.getString(R.string.models_downloaded_non_chat, model.name, out)
                }
            }.onFailure {
                show(context.getString(R.string.models_download_failed, it.message), isError = true)
            }
        }
    }

    fun setPendingPath(path: String) {
        _pendingPath.value = path
    }

    /**
     * Expected file/dir shape. Non-market models stay at their original path;
     * imports that match the market catalog move into the model-root layout
     * when the root does not already hold that entry (see `docs/MODEL_PATHS.md`).
     */
    fun engineFormatHint(engineId: EngineId): String = when (engineId) {
        EngineId.LITERT -> context.getString(R.string.models_format_litert)
        EngineId.MNN -> context.getString(R.string.models_format_mnn)
        EngineId.GENIE -> context.getString(R.string.models_format_genie)
        EngineId.LLAMACPP -> context.getString(R.string.models_format_llamacpp)
        EngineId.FAKE -> context.getString(R.string.common_any)
    }

    /**
     * Register [path] in the model list. Market-matching imports outside the
     * model root are relocated into the canonical market layout when free;
     * everything else stays where it is (engines open the registered path).
     *
     * @return whether the request passed validation and was queued; false keeps the
     * registration sheet open so the user can fix the form.
     */
    fun register(engineId: EngineId, displayName: String, path: String): Boolean {
        val name = displayName.trim()
        val raw = path.trim()
        if (name.isEmpty() || raw.isEmpty()) {
            show(context.getString(R.string.models_error_name_path_empty), isError = true)
            return false
        }
        val file = File(raw)
        if (!file.exists()) {
            show(context.getString(R.string.models_error_path_missing, raw), isError = true)
            return false
        }
        val result = FileFormatValidator.validatePath(engineId, file)
        if (result is ValidationResult.Failed) {
            show(context.getString(R.string.models_error_format, result.reason), isError = true)
            return false
        }
        val existing = models.value.firstOrNull { m ->
            (m.location as? ModelLocation.FilePath)?.path == file.absolutePath
        }
        if (existing != null) {
            show(context.getString(R.string.models_error_path_registered, existing.displayName), isError = true)
            return false
        }
        viewModelScope.launch {
            val (finalPath, note) = relocateCatalogMatch(engineId, file)
            upsertModel(engineId, name, finalPath)
            _message.value = context.getString(R.string.models_registered, name, note)
            _pendingPath.value = ""
        }
        return true
    }

    /**
     * When [file] is outside the model root but matches a market catalog entry
     * (same engine + basename) and the root does not already have that entry,
     * move it to [CatalogModel.canonicalRelPath]. Otherwise keep the original path.
     */
    private suspend fun relocateCatalogMatch(engineId: EngineId, file: File): Pair<String, String> {
        val abs = file.absolutePath
        val root = File(modelRoot())
        if (ModelRootMigrator.isSameOrNested(file, root)) {
            return abs to context.getString(R.string.models_note_original_path, abs)
        }
        val catalog = matchImported(
            models = _catalog.value.models,
            engineName = engineId.name,
            name = file.name,
        ) ?: return abs to context.getString(R.string.models_note_original_path, abs)
        // Root already holds a copy → leave the import alone, register as-is.
        if (findModelDir(catalog, root) != null) {
            return abs to context.getString(R.string.models_note_root_has_copy, abs)
        }
        val dest = File(root, catalog.canonicalRelPath())
        if (dest.exists()) {
            return abs to context.getString(R.string.models_note_target_exists, abs)
        }
        val ok = withContext(Dispatchers.IO) {
            ModelRootMigrator.moveItem(file, dest)
        }
        return if (ok) {
            dest.absolutePath to context.getString(R.string.models_note_moved, catalog.canonicalRelPath())
        } else {
            abs to context.getString(R.string.models_note_move_failed, abs)
        }
    }

    private suspend fun upsertModel(engineId: EngineId, displayName: String, path: String) {
        val file = File(path)
        modelStore.upsert(
            LocalModel(
                id = UUID.randomUUID().toString(),
                engineId = engineId,
                displayName = displayName,
                location = ModelLocation.FilePath(path),
                formatHint = formatHint(engineId),
                fileSizeBytes = if (file.isFile) file.length() else null,
            ),
        )
    }

    fun registerDownloaded(model: CatalogModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = _localPaths.value[model.id]?.let(::File)?.takeIf { it.exists() }
                ?: findModelDir(model, File(modelRoot()))
            if (file == null || !file.exists()) {
                show(context.getString(R.string.models_error_local_missing, model.name), isError = true)
                return@launch
            }
            val path = file.absolutePath
            val existing = models.value.firstOrNull { m ->
                (m.location as? ModelLocation.FilePath)?.path == path
            }
            if (existing != null) {
                show(context.getString(R.string.models_error_already_in_list, existing.displayName), isError = true)
                return@launch
            }
            register(
                engineId = EngineId.entries.firstOrNull { it.name.equals(model.engine, true) } ?: EngineId.LLAMACPP,
                displayName = model.name,
                path = path,
            )
        }
    }

    fun validate(modelId: String) {
        viewModelScope.launch {
            val model = models.value.firstOrNull { it.id == modelId } ?: return@launch
            val r = FileFormatValidator.validate(model.engineId, model.location)
            show(
                text = when (r) {
                    is ValidationResult.Ok -> context.getString(R.string.models_validate_ok, model.displayName)
                    is ValidationResult.Failed -> context.getString(R.string.models_validate_failed, r.reason)
                    ValidationResult.Unknown ->
                        context.getString(R.string.models_validate_unknown, model.displayName)
                },
                isError = r is ValidationResult.Failed,
            )
        }
    }

    fun delete(modelId: String) {
        viewModelScope.launch {
            modelStore.delete(modelId)
            _message.value = context.getString(R.string.models_deleted)
        }
    }

    fun update(modelId: String, displayName: String, path: String) {
        val name = displayName.trim()
        val newPath = path.trim()
        if (name.isEmpty() || newPath.isEmpty()) {
            show(context.getString(R.string.models_error_name_path_empty), isError = true)
            return
        }
        val current = models.value.firstOrNull { it.id == modelId }
        if (current == null) {
            show(context.getString(R.string.models_error_not_found), isError = true)
            return
        }
        val file = File(newPath)
        if (!file.exists()) {
            show(context.getString(R.string.models_error_path_missing, newPath), isError = true)
            return
        }
        val result = FileFormatValidator.validatePath(current.engineId, file)
        if (result is ValidationResult.Failed) {
            show(context.getString(R.string.models_error_format, result.reason), isError = true)
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
            _message.value = context.getString(R.string.models_updated, name)
        }
    }

    private fun formatHint(engineId: EngineId): String = engineFormatTag(engineId)
}

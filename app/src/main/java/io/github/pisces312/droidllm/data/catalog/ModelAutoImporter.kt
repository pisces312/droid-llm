package io.github.pisces312.droidllm.data.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** `formatHint` tag stored on a [LocalModel]; mirrors the engine's on-disk layout. */
fun engineFormatTag(engineId: EngineId): String = when (engineId) {
    EngineId.LITERT -> "litertlm"
    EngineId.MNN -> "mnn_dir"
    EngineId.GENIE -> "genie_dir"
    EngineId.LLAMACPP -> "gguf"
    EngineId.FAKE -> "fake"
}

/**
 * Registers catalog models that already exist on disk under a model root but are
 * absent from [ModelPathStore].
 *
 * Needed because the market scan ([findModelDir]) only reports "downloaded"; the
 * "已导入" tab and the chat model picker read the store. Without this, switching
 * the model root leaves both empty even though the files are recognized.
 */
@Singleton
class ModelAutoImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ModelPathStore,
) {

    /**
     * Scan [root] and register every matching catalog model that is not in the
     * store yet. Models the user deleted are re-added only when their files are
     * still present under the scanned root — that is the intent of a root switch.
     * @return number of newly registered models.
     */
    suspend fun registerFound(root: File): Int {
        val catalog = runCatching { ModelCatalogLoader.load(context) }
            .getOrElse { return 0 }
        val knownPaths = store.listModels()
            .mapNotNull { (it.location as? ModelLocation.FilePath)?.path }
            .toHashSet()
        var added = 0
        catalog.models.forEach { model ->
            if (model.tags.any { it == "ImageGen" || it == "AudioGen" }) return@forEach
            val engineId = EngineId.entries
                .firstOrNull { it.name.equals(model.engine, true) }
                ?: return@forEach
            val path = findModelDir(model, root)?.absolutePath ?: return@forEach
            if (path in knownPaths) return@forEach
            store.upsert(
                LocalModel(
                    id = UUID.randomUUID().toString(),
                    engineId = engineId,
                    displayName = model.name,
                    location = ModelLocation.FilePath(path),
                    formatHint = engineFormatTag(engineId),
                    fileSizeBytes = File(path).takeIf { it.isFile }?.length(),
                ),
            )
            knownPaths += path
            added++
        }
        return added
    }
}

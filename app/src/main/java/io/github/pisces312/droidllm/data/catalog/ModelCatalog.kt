package io.github.pisces312.droidllm.data.catalog

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogSourceMap(
    val HuggingFace: String? = null,
    val ModelScope: String? = null,
)

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val engine: String,
    val vendor: String = "",
    val description: String = "",
    val sizeBytes: Long = 0,
    /** "file" = single model file; "repo" = whole repo into a folder. */
    val kind: String = "file",
    /** Relative path under the engine folder (file name or directory name). */
    val localPath: String,
    /** Repo-relative file path when [kind] == "file". */
    val fileInRepo: String? = null,
    /** Marker file that must exist for a "repo" download to count as present. */
    val markerFile: String? = null,
    val sources: CatalogSourceMap = CatalogSourceMap(),
) {
    fun repoPath(source: ModelSource): String? = when (source) {
        ModelSource.HuggingFace -> sources.HuggingFace
        ModelSource.ModelScope -> sources.ModelScope
    }
}

@Serializable
data class ModelCatalog(
    val version: Int = 1,
    val models: List<CatalogModel> = emptyList(),
)

enum class ModelSource(val displayName: String) {
    HuggingFace("HuggingFace"),
    ModelScope("ModelScope"),
    ;

    companion object {
        fun from(raw: String?): ModelSource =
            entries.firstOrNull { it.name == raw } ?: HuggingFace
    }
}

object ModelCatalogLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): ModelCatalog {
        val text = context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(ModelCatalog.serializer(), text)
    }
}

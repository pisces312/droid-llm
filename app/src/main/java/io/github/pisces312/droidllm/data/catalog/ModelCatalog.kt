package io.github.pisces312.droidllm.data.catalog

import android.content.Context
import java.io.File
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
    /**
     * - "file": single model file inside the cache snapshot dir
     * - "repo" / "mnn_repo": whole repo into the cache snapshot dir
     *   (`{sourceDir}/models--{org}--{repo}/snapshots/_no_sha_/`)
     */
    val kind: String = "file",
    /** Relative path under the engine folder (file name, directory name, or fallback repo name). */
    val localPath: String,
    /** Repo-relative file path when [kind] == "file". */
    val fileInRepo: String? = null,
    /** Marker file that must exist for a "repo" download to count as present. */
    val markerFile: String? = null,
    val sources: CatalogSourceMap = CatalogSourceMap(),
    val tags: List<String> = emptyList(),
) {
    fun repoPath(source: ModelSource): String? = when {
        source.isHuggingFace -> sources.HuggingFace
        else -> sources.ModelScope
    }

    /**
     * Where this download should land, relative to the model root.
     * Every HF/ModelScope download uses the MnnLlmChat cache layout
     * (`{sourceDir}/models--{org}--{repo}/snapshots/_no_sha_/…`) so all engines
     * share one storage convention. Third-party imports use `{engine}/{name}`.
     */
    fun downloadRelPath(source: ModelSource): String {
        val repo = repoPath(source)
        if (repo != null) {
            val snap = "${source.cacheDir}/${repoFolderName(repo)}/snapshots/_no_sha_"
            return if (kind == "file") {
                "$snap/${fileInRepo ?: localPath.substringAfterLast('/')}"
            } else {
                snap
            }
        }
        return localPath
    }

    /** Relative candidate paths (under model root) that count as "already downloaded". */
    fun candidateRelPaths(): List<String> {
        val out = linkedSetOf<String>()
        val fileName = fileInRepo ?: localPath.substringAfterLast('/')
        listOfNotNull(sources.HuggingFace, sources.ModelScope).forEach { repo ->
            val folder = repoFolderName(repo)
            val dirs = if (repo == sources.ModelScope) {
                listOf("modelscope", "mnn/modelscope")
            } else {
                listOf("hf", "mnn/hf")
            }
            dirs.forEach { d ->
                if (kind == "file") {
                    out += "$d/$folder/snapshots/_no_sha_/$fileName"
                    out += "$d/$folder/snapshots" // any sha; findModelDir searches children
                } else {
                    out += "$d/$folder/snapshots/_no_sha_"
                    out += "$d/$folder/snapshots"
                }
            }
        }
        if (kind == "file") {
            val engine = engine.lowercase()
            out += "$engine/$localPath"
            out += localPath
        } else {
            // Legacy / alternate layouts (MnnLlmChat symlink mode, old engine subdir).
            out += "mnn/$localPath"
            out += "modelscope/$localPath"
            out += "${engine.lowercase()}/$localPath"
            out += localPath
        }
        return out.toList()
    }

    companion object {
        /** `org/name` → `models--org--name` (MnnLlmChat / HF cache convention). */
        fun repoFolderName(repo: String): String =
            "models--" + repo.split("/").filter { it.isNotEmpty() }.joinToString("--")
    }
}

@Serializable
data class ModelCatalog(
    val version: Int = 1,
    val models: List<CatalogModel> = emptyList(),
)

/**
 * Download server. HuggingFace official and mirror share the same catalog repo
 * id (`sources.HuggingFace`) and only differ by host; both write under `hf/`.
 */
enum class ModelSource(val displayName: String, val host: String, val cacheDir: String) {
    HuggingFace("HF官方", "https://huggingface.co", "hf"),
    HuggingFaceMirror("HF镜像", "https://hf-mirror.com", "hf"),
    ModelScope("ModelScope", "https://modelscope.cn", "modelscope"),
    ;

    val isHuggingFace: Boolean
        get() = this == HuggingFace || this == HuggingFaceMirror

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

/**
 * Resolve an on-disk file or directory for [model] under [root], or null when absent.
 * Accepts the MnnLlmChat cache layout (with or without an `mnn/` engine prefix)
 * and the plain engine-subdir layout used for third-party imports.
 */
fun findModelDir(model: CatalogModel, root: File): File? {
    val marker = model.markerFile ?: "config.json"
    val fileName = model.fileInRepo ?: model.localPath.substringAfterLast('/')
    for (rel in model.candidateRelPaths()) {
        val dir = File(root, rel)
        if (model.kind == "file") {
            if (dir.isFile && dir.length() > 0) return dir
            if (dir.isDirectory) {
                File(dir, fileName).takeIf { it.isFile && it.length() > 0 }?.let { return it }
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        File(child, fileName).takeIf { it.isFile && it.length() > 0 }?.let { return it }
                    }
                }
            }
            continue
        }
        // repo / mnn_repo: marker file, or a snapshots child with marker, or non-empty dir
        if (File(dir, marker).exists()) return dir
        if (dir.name == "snapshots" && dir.isDirectory) {
            val snap = dir.listFiles()?.firstOrNull { it.isDirectory && File(it, marker).exists() }
            if (snap != null) return snap
        }
        if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
            if (rel.endsWith("/snapshots")) {
                val snap = dir.listFiles()?.firstOrNull { it.isDirectory && File(it, marker).exists() }
                if (snap != null) return snap
            }
            return dir
        }
    }
    return null
}

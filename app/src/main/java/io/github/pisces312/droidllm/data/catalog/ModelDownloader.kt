package io.github.pisces312.droidllm.data.catalog

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class DownloadStatus {
    NOT_START,
    DOWNLOADING,
    SUCCESS,
    FAILED,
}

data class DownloadState(
    val status: DownloadStatus = DownloadStatus.NOT_START,
    val progress: Float = 0f,
    val message: String = "",
)

/**
 * Minimal HF / ModelScope downloader (no hub cache layout).
 * Files land directly under the shared model root, matching [CatalogModel.localPath].
 */
class ModelDownloader {

    private val json = Json { ignoreUnknownKeys = true }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    @Volatile
    private var activeId: String? = null

    fun stateOf(id: String): DownloadState = _states.value[id] ?: DownloadState()

    suspend fun download(
        model: CatalogModel,
        source: ModelSource,
        target: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        val key = model.id
        if (activeId != null && activeId != key) {
            return@withContext Result.failure(IllegalStateException("已有下载任务进行中"))
        }
        activeId = key
        update(key, DownloadStatus.DOWNLOADING, 0f, "准备中")
        try {
            val repo = model.repoPath(source)
                ?: return@withContext fail(key, "当前源无此模型").let { Result.failure(it) }
            val out = when (model.kind) {
                "repo" -> downloadRepo(model, source, repo, target)
                else -> downloadSingle(model, source, repo, target)
            }
            update(key, DownloadStatus.SUCCESS, 1f, "完成")
            Result.success(out)
        } catch (e: Exception) {
            update(key, DownloadStatus.FAILED, 0f, e.message ?: "下载失败")
            Result.failure(e)
        } finally {
            activeId = null
        }
    }

    private fun fail(id: String, msg: String): Exception {
        update(id, DownloadStatus.FAILED, 0f, msg)
        return IllegalStateException(msg)
    }

    private fun downloadSingle(
        model: CatalogModel,
        source: ModelSource,
        repo: String,
        target: File,
    ): File {
        val remote = model.fileInRepo ?: model.localPath
        val out = File(target, model.localPath)
        out.parentFile?.mkdirs()
        val url = resolveFileUrl(source, repo, remote)
        fetchToFile(url, out, model.id)
        return out
    }

    private fun downloadRepo(
        model: CatalogModel,
        source: ModelSource,
        repo: String,
        target: File,
    ): File {
        val dir = File(target, model.localPath)
        dir.mkdirs()
        val files = listRepoFiles(source, repo)
        if (files.isEmpty()) throw IllegalStateException("仓库文件列表为空")
        files.forEachIndexed { index, path ->
            val out = File(dir, path)
            out.parentFile?.mkdirs()
            fetchToFile(resolveFileUrl(source, repo, path), out, model.id)
            val progress = (index + 1).toFloat() / files.size
            update(model.id, DownloadStatus.DOWNLOADING, progress, "下载 ${index + 1}/${files.size}")
        }
        return dir
    }

    private fun resolveFileUrl(source: ModelSource, repo: String, path: String): String = when (source) {
        ModelSource.HuggingFace ->
            "https://huggingface.co/$repo/resolve/main/$path"
        ModelSource.ModelScope ->
            "https://modelscope.cn/api/v1/models/$repo/repo?FilePath=$path"
    }

    private fun listRepoFiles(source: ModelSource, repo: String): List<String> {
        val body = when (source) {
            ModelSource.HuggingFace -> {
                val url = "https://huggingface.co/api/models/$repo/tree/main?recursive=true"
                readText(url)
            }
            ModelSource.ModelScope -> {
                val url = "https://modelscope.cn/api/v1/models/$repo/repo/files?Recursive=1"
                readText(url)
            }
        }
        return parseFileList(source, body)
    }

    private fun parseFileList(source: ModelSource, body: String): List<String> = when (source) {
        ModelSource.HuggingFace -> {
            val items = json.decodeFromString<List<HfTreeItem>>(body)
            items.filter { it.type == "file" && !it.path.startsWith(".") }
                .map { it.path }
        }
        ModelSource.ModelScope -> {
            val wrapper = json.decodeFromString<MsFilesResponse>(body)
            wrapper.Data?.Files?.mapNotNull { it.Path }?.filter { !it.startsWith(".") }.orEmpty()
        }
    }

    private fun readText(urlStr: String): String {
        val conn = open(urlStr)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}: $urlStr")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchToFile(urlStr: String, out: File, id: String) {
        val tmp = File(out.parentFile, out.name + ".part")
        val conn = open(urlStr)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}: $urlStr")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var saved = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        saved += n
                        if (total > 0) {
                            val p = (saved.toFloat() / total).coerceIn(0f, 1f)
                            update(id, DownloadStatus.DOWNLOADING, p, "下载 ${out.name}")
                        }
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
            if (tmp.exists() && !tmp.renameTo(out)) tmp.delete()
        }
    }

    private fun open(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "droid-llm/0.1")
        return conn
    }

    private fun update(id: String, status: DownloadStatus, progress: Float, message: String) {
        _states.value = _states.value + (id to DownloadState(status, progress, message))
    }
}

@Serializable
private data class HfTreeItem(
    val path: String = "",
    val type: String = "",
)

@Serializable
private data class MsFilesResponse(
    val Data: MsFilesData? = null,
)

@Serializable
private data class MsFilesData(
    val Files: List<MsFile>? = null,
)

@Serializable
private data class MsFile(
    val Path: String? = null,
)

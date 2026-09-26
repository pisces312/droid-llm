package io.github.pisces312.droidllm.common.model

import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for user-configured local models (one or more per engine).
 */
interface ModelPathStore {
    fun observeModels(): Flow<List<LocalModel>>
    suspend fun upsert(model: LocalModel)
    suspend fun delete(modelId: String)
    suspend fun listModels(): List<LocalModel>
    suspend fun listModels(engineId: EngineId): List<LocalModel>
}

/**
 * Fast format detection before load. Returns null when the location cannot be
 * inspected (e.g. SAF without permission); the adapter then does a full load
 * and reports real errors.
 */
interface ModelFormatValidator {
    fun validate(engineId: EngineId, location: ModelLocation): ValidationResult
}

sealed class ValidationResult {
    data object Ok : ValidationResult()
    data class Failed(val reason: String) : ValidationResult()
    data object Unknown : ValidationResult()
}

/** Filesystem-backed validators used for FilePath / AppPrivate locations. */
object FileFormatValidator : ModelFormatValidator {

    override fun validate(engineId: EngineId, location: ModelLocation): ValidationResult {
        return when (location) {
            is ModelLocation.SafUri -> ValidationResult.Unknown
            is ModelLocation.FilePath -> validatePath(engineId, File(location.path))
            is ModelLocation.AppPrivate -> ValidationResult.Unknown
        }
    }

    fun validatePath(engineId: EngineId, file: File): ValidationResult {
        return when (engineId) {
            EngineId.LITERT -> validateLitert(file)
            EngineId.MNN -> validateMnnDir(file)
            EngineId.GENIE -> validateGenieDir(file)
            EngineId.LLAMACPP -> validateGguf(file)
            EngineId.FAKE -> ValidationResult.Ok
        }
    }

    private fun validateLitert(file: File): ValidationResult {
        if (!file.isFile) return ValidationResult.Failed("not a file: ${file.path}")
        val lower = file.name.lowercase()
        if (!lower.endsWith(".litertlm") && !lower.endsWith(".task")) {
            return ValidationResult.Failed("expect .litertlm or .task, got ${file.name}")
        }
        return ValidationResult.Ok
    }

    private fun validateMnnDir(file: File): ValidationResult {
        if (!file.isDirectory) return ValidationResult.Failed("not a directory: ${file.path}")
        val config = File(file, "config.json")
        val model = File(file, "llm.mnn")
        return when {
            !config.isFile -> ValidationResult.Failed("missing config.json in ${file.path}")
            !model.isFile -> ValidationResult.Failed("missing llm.mnn in ${file.path}")
            else -> ValidationResult.Ok
        }
    }

    private fun validateGenieDir(file: File): ValidationResult {
        if (!file.isDirectory) return ValidationResult.Failed("not a directory: ${file.path}")
        val config = File(file, "genie_config.json")
        val tokenizer = File(file, "tokenizer.json")
        val bins = file.listFiles { f -> f.isFile && f.extension.equals("bin", true) }.orEmpty()
        return when {
            !config.isFile -> ValidationResult.Failed("missing genie_config.json in ${file.path}")
            !tokenizer.isFile -> ValidationResult.Failed("missing tokenizer.json in ${file.path}")
            bins.isEmpty() -> ValidationResult.Failed("missing *.bin context binary in ${file.path}")
            else -> ValidationResult.Ok
        }
    }

    private fun validateGguf(file: File): ValidationResult {
        if (!file.isFile) return ValidationResult.Failed("not a file: ${file.path}")
        if (!file.name.lowercase().endsWith(".gguf")) {
            return ValidationResult.Failed("expect .gguf, got ${file.name}")
        }
        if (file.length() < 4) return ValidationResult.Failed("file too small")
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        val magic = String(header, Charsets.US_ASCII)
        return if (magic == "GGUF") ValidationResult.Ok
        else ValidationResult.Failed("bad GGUF magic: $magic")
    }
}

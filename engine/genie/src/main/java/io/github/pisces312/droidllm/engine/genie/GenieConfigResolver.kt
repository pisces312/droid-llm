package io.github.pisces312.droidllm.engine.genie

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Resolves HTP backend extension config by [Build.SOC_MODEL]
 * (table from chatapp_android) and rewrites `genie_config.json` with absolute
 * tokenizer / ctx-bin / extensions paths.
 */
@Singleton
class GenieConfigResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return absolute path of the HTP backend extension json for this device,
     *   or null when the SoC is not in the supported table.
     */
    fun resolveHtpConfigPath(): String? {
        val fileName = SOC_TO_HTP[Build.SOC_MODEL] ?: return null
        val target = File(File(context.filesDir, "htp_config"), fileName)
        if (!target.isFile) {
            try {
                context.assets.open("htp_config/$fileName").use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: IOException) {
                return null
            }
        }
        return target.absolutePath
    }

    fun supportedSocs(): List<String> = SOC_TO_HTP.keys.sorted()

    /**
     * Validate a Genie model directory (AI Hub / chatapp layout).
     * @return null when valid, otherwise a user-readable reason.
     */
    fun validateModelDir(dir: File): String? {
        if (!dir.isDirectory) return "模型目录不存在: ${dir.absolutePath}"
        if (!File(dir, "genie_config.json").isFile) {
            return "缺少 genie_config.json"
        }
        if (!File(dir, "tokenizer.json").isFile) {
            return "缺少 tokenizer.json"
        }
        val hasBin = dir.listFiles()?.any { it.isFile && it.name.endsWith(".bin") } == true
        if (!hasBin) return "缺少 *.bin 上下文二进制"
        return null
    }

    /**
     * Rewrite genie_config.json with absolute paths for this load.
     * Mirrors chatapp_android LoadModelConfig.
     */
    fun buildResolvedConfig(modelDir: File, htpConfigPath: String, configOverrides: ConfigOverrides): String {
        val configFile = File(modelDir, "genie_config.json")
        val root = json.parseToJsonElement(configFile.readText()).jsonObject
        val dialog = root["dialog"]?.jsonObject
            ?: throw IllegalStateException("genie_config.json missing dialog")

        val tokenizerPath = File(modelDir, "tokenizer.json").absolutePath
        val modelDirPath = modelDir.absolutePath

        val newDialog = buildJsonObject {
            for ((k, v) in dialog) {
                when (k) {
                    "tokenizer" -> put(k, patchTokenizer(v, tokenizerPath))
                    "engine" -> put(k, patchEngine(v, modelDirPath, htpConfigPath, configOverrides))
                    "sampler" -> put(k, patchSampler(v, configOverrides))
                    else -> put(k, v)
                }
            }
        }
        return buildJsonObject {
            for ((k, v) in root) {
                put(k, if (k == "dialog") newDialog else v)
            }
        }.toString()
    }

    data class ConfigOverrides(
        val temperature: Double? = null,
        val topK: Int? = null,
        val topP: Double? = null,
        val seed: Long? = null,
        val maxNewTokens: Int? = null,
    )

    private fun patchTokenizer(v: JsonElement, tokenizerPath: String): JsonElement {
        val obj = v.jsonObject
        return buildJsonObject {
            for ((k, value) in obj) {
                put(k, if (k == "path") JsonPrimitive(tokenizerPath) else value)
            }
            if (!obj.containsKey("path")) put("path", tokenizerPath)
        }
    }

    private fun patchEngine(
        v: JsonElement,
        modelDirPath: String,
        htpConfigPath: String,
        overrides: ConfigOverrides,
    ): JsonElement {
        val obj = v.jsonObject
        return buildJsonObject {
            for ((k, value) in obj) {
                when (k) {
                    "backend" -> put(k, patchBackend(value, htpConfigPath))
                    "model" -> put(k, patchModel(value, modelDirPath))
                    "n-threads" -> {
                        // Keep engine's own thread setting; we only warn in metrics.
                        put(k, value)
                    }
                    else -> put(k, value)
                }
            }
        }
    }

    private fun patchBackend(v: JsonElement, htpConfigPath: String): JsonElement {
        val obj = v.jsonObject
        return buildJsonObject {
            for ((k, value) in obj) {
                put(k, if (k == "extensions") JsonPrimitive(htpConfigPath) else value)
            }
            if (!obj.containsKey("extensions")) put("extensions", htpConfigPath)
        }
    }

    private fun patchModel(v: JsonElement, modelDirPath: String): JsonElement {
        val obj = v.jsonObject
        return buildJsonObject {
            for ((k, value) in obj) {
                if (k == "binary") {
                    put(k, patchBinary(value, modelDirPath))
                } else {
                    put(k, value)
                }
            }
        }
    }

    private fun patchBinary(v: JsonElement, modelDirPath: String): JsonElement {
        val obj = v.jsonObject
        return buildJsonObject {
            for ((k, value) in obj) {
                if (k == "ctx-bins") {
                    val arr = value as? JsonArray ?: JsonArray(emptyList())
                    put(
                        k,
                        buildJsonArray {
                            for (item in arr) {
                                val name = item.jsonPrimitive.contentOrNull.orEmpty()
                                val abs = if (name.isEmpty()) name else "$modelDirPath/$name"
                                add(JsonPrimitive(abs))
                            }
                        },
                    )
                } else {
                    put(k, value)
                }
            }
        }
    }

    private fun patchSampler(v: JsonElement, overrides: ConfigOverrides): JsonElement {
        val obj = v.jsonObject
        return buildJsonObject {
            for ((k, value) in obj) {
                val replacement: JsonElement? = when {
                    k == "temp" && overrides.temperature != null -> JsonPrimitive(overrides.temperature)
                    k == "top-k" && overrides.topK != null -> JsonPrimitive(overrides.topK)
                    k == "top-p" && overrides.topP != null -> JsonPrimitive(overrides.topP)
                    k == "seed" && overrides.seed != null -> JsonPrimitive(overrides.seed)
                    else -> null
                }
                put(k, replacement ?: value)
            }
            if (overrides.temperature != null && !obj.containsKey("temp")) {
                put("temp", overrides.temperature)
            }
            if (overrides.topK != null && !obj.containsKey("top-k")) {
                put("top-k", overrides.topK)
            }
            if (overrides.topP != null && !obj.containsKey("top-p")) {
                put("top-p", overrides.topP)
            }
            if (overrides.seed != null && !obj.containsKey("seed")) {
                put("seed", overrides.seed)
            }
        }
    }

    companion object {
        /**
         * SOC_MODEL → htp_config asset. From chatapp_android MainActivity.
         * SM8850/SM8750 = 8 Elite, SM8650 = 8 Gen3, QCS8550 = 8 Gen2.
         */
        val SOC_TO_HTP: Map<String, String> = mapOf(
            "SM8850" to "qualcomm-snapdragon-8-elite.json",
            "SM8750" to "qualcomm-snapdragon-8-elite.json",
            "SM8650" to "qualcomm-snapdragon-8-gen3.json",
            "QCS8550" to "qualcomm-snapdragon-8-gen2.json",
        )
    }
}

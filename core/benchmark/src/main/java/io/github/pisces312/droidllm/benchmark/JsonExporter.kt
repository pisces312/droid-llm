package io.github.pisces312.droidllm.benchmark

import io.github.pisces312.droidllm.engineapi.ModelLocation
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export a [BenchmarkReport] as one JSON file under
 * `getExternalFilesDir("benchmark")/` (DESIGN.md §2.1-5).
 */
object JsonExporter {

    fun exportToJson(report: BenchmarkReport): JSONObject {
        return JSONObject().apply {
            put("runId", report.runId)
            put("timestampMs", report.timestampMs)
            put("spec", report.specSummary)
            put("soc", report.soc ?: JSONObject.NULL)
            put("sdkInt", report.sdkInt)
            put(
                "disclaimer",
                "跨模型/跨量化数字只作参考，不构成引擎绝对快慢结论。",
            )
            put(
                "targets",
                JSONArray().apply {
                    report.targets.forEach { t ->
                        put(
                            JSONObject().apply {
                                put("engineId", t.engineId.name)
                                put("engineDisplayName", t.engineDisplayName)
                                put("modelName", t.model.displayName)
                                put("modelPath", pathOf(t.model))
                                put("quantHint", t.model.quantHint ?: JSONObject.NULL)
                                put("formatHint", t.model.formatHint ?: JSONObject.NULL)
                                put("rssMbBaseline", t.rssMbBaseline ?: JSONObject.NULL)
                                put("rssMbLoad", t.rssMbLoad ?: JSONObject.NULL)
                                put("rssMbPeak", t.rssMbPeak ?: JSONObject.NULL)
                                put("tempCStart", t.tempCStart ?: JSONObject.NULL)
                                put("tempCEnd", t.tempCEnd ?: JSONObject.NULL)
                                put("error", t.error ?: JSONObject.NULL)
                                put("warnings", JSONArray(t.warnings))
                                put(
                                    "cases",
                                    JSONObject().apply {
                                        for ((id, case) in t.cases) {
                                            put(
                                                id.name,
                                                JSONObject().apply {
                                                    put("case", id.name)
                                                    put("loadMs", case.loadMs ?: JSONObject.NULL)
                                                    put("ttftMs", case.ttftMs ?: JSONObject.NULL)
                                                    put("prefillTps", case.prefillTps ?: JSONObject.NULL)
                                                    put("decodeTps", case.decodeTps ?: JSONObject.NULL)
                                                    put(
                                                        "perTokenMsP50",
                                                        case.perTokenMsP50 ?: JSONObject.NULL,
                                                    )
                                                    put(
                                                        "samples",
                                                        JSONArray().apply {
                                                            case.samples.forEach { s ->
                                                                put(
                                                                    JSONObject().apply {
                                                                        put(
                                                                            "ttftMs",
                                                                            s.ttftMs ?: JSONObject.NULL,
                                                                        )
                                                                        put(
                                                                            "decodeTps",
                                                                            s.decodeTps ?: JSONObject.NULL,
                                                                        )
                                                                        put(
                                                                            "generatedTokens",
                                                                            s.generatedTokens,
                                                                        )
                                                                        put(
                                                                            "textPreview",
                                                                            s.textPreview,
                                                                        )
                                                                        put(
                                                                            "error",
                                                                            s.error ?: JSONObject.NULL,
                                                                        )
                                                                    },
                                                                )
                                                            }
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    /**
     * Write JSON to [dir] (typically `getExternalFilesDir("benchmark")`).
     * @return the created file.
     */
    fun write(report: BenchmarkReport, dir: File): File {
        dir.mkdirs()
        val name = "bench_${report.timestampMs}_${report.runId.take(8)}.json"
        val file = File(dir, name)
        file.writeText(exportToJson(report).toString(2))
        return file
    }

    private fun pathOf(model: io.github.pisces312.droidllm.engineapi.LocalModel): String =
        when (val loc = model.location) {
            is ModelLocation.FilePath -> loc.path
            is ModelLocation.AppPrivate -> loc.relativePath
            is ModelLocation.SafUri -> loc.uri
        }
}

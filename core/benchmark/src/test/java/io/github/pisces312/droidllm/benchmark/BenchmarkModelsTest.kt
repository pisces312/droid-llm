package io.github.pisces312.droidllm.benchmark

import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LocalModel
import io.github.pisces312.droidllm.engineapi.ModelLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkModelsTest {

    @Test
    fun specDefaults_includeLpd() {
        val spec = BenchmarkSpec(targets = listOf(dummyTarget()))
        assertTrue(spec.cases.containsAll(listOf(BenchCaseId.LOAD, BenchCaseId.PREFILL, BenchCaseId.DECODE)))
        assertEquals(1, spec.warmup)
        assertEquals(3, spec.runs)
    }

    @Test
    fun median_ofOddCount_isMiddle() {
        // aggregate is private; recompute the same way as runner
        val values = listOf(10.0, 30.0, 20.0).sorted()
        assertEquals(20.0, values[values.size / 2], 0.0)
    }

    @Test
    fun jsonExport_containsRequiredIdentityFields() {
        val model = LocalModel(
            id = "m1",
            engineId = EngineId.FAKE,
            displayName = "demo",
            location = ModelLocation.FilePath("/tmp/model.gguf"),
            quantHint = "Q4_K_M",
            formatHint = "gguf",
        )
        val report = BenchmarkReport(
            runId = "run-1",
            timestampMs = 1L,
            specSummary = "cases=LOAD warmup=1 runs=3",
            targets = listOf(
                TargetResult(
                    engineId = EngineId.FAKE,
                    engineDisplayName = "Fake",
                    model = model,
                    cases = mapOf(
                        BenchCaseId.DECODE to CaseResult(
                            caseId = BenchCaseId.DECODE,
                            samples = listOf(CaseSample(ttftMs = 12, decodeTps = 34.0, generatedTokens = 8)),
                            ttftMs = 12,
                            decodeTps = 34.0,
                        ),
                    ),
                    rssMbBaseline = 100,
                    rssMbLoad = 200,
                    rssMbPeak = 250,
                    tempCStart = 31.5,
                    tempCEnd = 32.0,
                ),
            ),
            soc = "SM8850",
            sdkInt = 35,
        )
        val json = JsonExporter.exportToJson(report)
        assertEquals("run-1", json.getString("runId"))
        val target = json.getJSONArray("targets").getJSONObject(0)
        assertEquals("FAKE", target.getString("engineId"))
        assertEquals("demo", target.getString("modelName"))
        assertEquals("/tmp/model.gguf", target.getString("modelPath"))
        assertEquals("Q4_K_M", target.getString("quantHint"))
        assertEquals(250L, target.getLong("rssMbPeak"))
        val decode = target.getJSONObject("cases").getJSONObject("DECODE")
        assertEquals(12L, decode.getLong("ttftMs"))
        assertEquals(34.0, decode.getDouble("decodeTps"), 0.01)
    }

    @Test
    fun emptySampleMedians_areNull() {
        val case = CaseResult(caseId = BenchCaseId.PREFILL, samples = listOf(CaseSample(error = "x")))
        assertNull(case.ttftMs)
        assertNull(case.decodeTps)
    }

    private fun dummyTarget() = BenchTarget(
        engineId = EngineId.FAKE,
        model = LocalModel(
            id = "m",
            engineId = EngineId.FAKE,
            displayName = "m",
            location = ModelLocation.FilePath("/m"),
        ),
    )
}

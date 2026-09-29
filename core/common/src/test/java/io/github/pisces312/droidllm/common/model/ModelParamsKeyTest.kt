package io.github.pisces312.droidllm.common.model

import io.github.pisces312.droidllm.engineapi.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Key rules for the per-model overlay.
 *
 * The engine is part of a model's identity in this store, which is what makes
 * "the same model pinned differently under two engines" expressible at all.
 * `find` also has to keep reading the pre-engine layout, because a bundle
 * exported (or a DataStore written) before the change stores bare model ids.
 */
class ModelParamsKeyTest {

    @Test
    fun `engine is part of the key`() {
        assertNotEquals(
            modelParamsKey(EngineId.MNN, "model-1"),
            modelParamsKey(EngineId.LITERT, "model-1"),
        )
    }

    @Test
    fun `the same model keeps a separate overlay per engine`() {
        // The whole point of the change: one model file registered under two
        // engines must not let one engine's pin leak into the other. Gemma 3
        // wants 1.0/64 on LiteRT but Qualcomm pins 0.8/top-k 1 on NPU.
        val store = mapOf(
            modelParamsKey(EngineId.MNN, "model-1") to ModelParamsOverride(temperature = 0.6f),
            modelParamsKey(EngineId.LITERT, "model-1") to ModelParamsOverride(temperature = 1.0f),
        )
        assertEquals(0.6f, store.find(EngineId.MNN, "model-1")?.temperature)
        assertEquals(1.0f, store.find(EngineId.LITERT, "model-1")?.temperature)
    }

    @Test
    fun `engine scoped key wins over the legacy bare key`() {
        // Both present: the scoped entry is the live one, the bare one is the
        // stale v1 leftover that `set` is expected to drop.
        val store = mapOf(
            "model-1" to ModelParamsOverride(topK = 7),
            modelParamsKey(EngineId.MNN, "model-1") to ModelParamsOverride(topK = 99),
        )
        assertEquals(99, store.find(EngineId.MNN, "model-1")?.topK)
    }

    @Test
    fun `legacy bare modelId key is still read`() {
        val legacy = mapOf("model-1" to ModelParamsOverride(topK = 7))
        assertEquals(7, legacy.find(EngineId.MNN, "model-1")?.topK)
    }

    @Test
    fun `no entry yields null`() {
        assertNull(emptyMap<String, ModelParamsOverride>().find(EngineId.MNN, "model-1"))
    }

    @Test
    fun `an entry for another model is not returned`() {
        val store = mapOf(
            modelParamsKey(EngineId.MNN, "model-1") to ModelParamsOverride(topK = 7),
        )
        assertNull(store.find(EngineId.MNN, "model-2"))
    }
}

package io.github.pisces312.droidllm.common.model

import io.github.pisces312.droidllm.engineapi.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StoredModelTest {

    private fun stored(engineId: String) = StoredModel(
        id = "m1",
        engineId = engineId,
        displayName = "M1",
        locationType = "file",
        locationValue = "/tmp/m1",
    )

    @Test
    fun `known engine id maps back to the enum`() {
        val local = stored("MNN").toLocal()
        assertNotNull(local)
        assertEquals(EngineId.MNN, local!!.engineId)
    }

    @Test
    fun `unknown engine id is skipped instead of throwing`() {
        // Regression for CODE_REVIEW B5: EngineId.valueOf used to throw and take
        // down observeModels()/listModels() for every consumer.
        assertNull(stored("NOT_AN_ENGINE").toLocal())
        assertNull(stored("").toLocal())
    }

    @Test
    fun `storage lookup is case-insensitive`() {
        assertEquals(EngineId.LLAMACPP, stored("llamacpp").toLocal()!!.engineId)
    }
}

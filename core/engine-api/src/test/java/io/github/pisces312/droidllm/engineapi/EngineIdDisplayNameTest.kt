package io.github.pisces312.droidllm.engineapi

import io.github.pisces312.droidllm.engineapi.fake.FakeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the single source of truth for engine names.
 *
 * Every UI surface derives its label from [EngineId.displayName], so a missing case or a blank
 * value would resurface the "same engine, six spellings" defect (`LITERT` / `LLAMACPP` next to
 * `MNN 3.6.1 #c0461933`). Persisted rows round-trip through [engineIdFromStorage].
 */
class EngineIdDisplayNameTest {

    @Test
    fun displayName_isNonBlankForEveryId() {
        EngineId.entries.forEach { id ->
            assertTrue("blank displayName for $id", id.displayName.isNotBlank())
        }
    }

    @Test
    fun adapters_reportTheSharedDisplayName() {
        // The test-only double must not drift from the shared table either.
        assertEquals(EngineId.FAKE.displayName, FakeEngine().displayName)
    }

    @Test
    fun fromStorage_roundTripsThePersistedName() {
        EngineId.entries.forEach { id ->
            assertEquals(id, engineIdFromStorage(id.name))
        }
        // Case-insensitive, and unknown/absent values stay unresolved instead of guessing.
        assertEquals(EngineId.LITERT, engineIdFromStorage("litert"))
        assertNull(engineIdFromStorage(null))
        assertNull(engineIdFromStorage("SOME_FUTURE_ENGINE"))
    }
}

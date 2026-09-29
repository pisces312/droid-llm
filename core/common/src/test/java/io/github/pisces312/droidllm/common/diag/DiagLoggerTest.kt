package io.github.pisces312.droidllm.common.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagLoggerTest {

    /**
     * Regression guard for a real crash: the log screen keyed its LazyColumn on
     * `timestampMs + message.hashCode()`, and the four engines report the same
     * probe verdict inside the same millisecond, which produced duplicate keys
     * ("Key ... was already used") and took the whole app down. The key is now
     * [DiagEntry.seq], so uniqueness of seq is the invariant that matters.
     */
    @Test
    fun `seq stays unique for identical text written in the same millisecond`() {
        DiagLogger.clear()

        repeat(4) { DiagLogger.i("engine/probe", "UnsupportedSoc(ranchu)") }

        val entries = DiagLogger.entries.value
        assertEquals(4, entries.size)
        assertEquals(
            "list keys are derived from seq, so seq must never collide",
            entries.size,
            entries.map { it.seq }.toSet().size,
        )
    }

    @Test
    fun `seq keeps increasing across clears`() {
        DiagLogger.clear()
        DiagLogger.i("t", "before")
        val first = DiagLogger.entries.value.single().seq

        DiagLogger.clear()
        DiagLogger.i("t", "after")

        assertTrue(DiagLogger.entries.value.single().seq > first)
    }

    @Test
    fun `clear empties the buffer`() {
        DiagLogger.i("t", "x")
        DiagLogger.clear()
        assertTrue(DiagLogger.entries.value.isEmpty())
    }
}

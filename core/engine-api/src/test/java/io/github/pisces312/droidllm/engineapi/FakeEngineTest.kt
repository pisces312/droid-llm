package io.github.pisces312.droidllm.engineapi

import io.github.pisces312.droidllm.engineapi.fake.FakeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEngineTest {

    @Test
    fun generateEmitsTokensAndMetrics() = runBlocking {
        val engine = FakeEngine()
        val probe = ProbeContext(
            socModel = "test",
            sdkInt = 35,
            hasOpenCl = false,
            hasCdspRpc = false,
            totalRamMb = 8192,
            availableStorageMb = 10240,
        )
        assertTrue(engine.probe(probe) is Availability.Available)

        val model = LocalModel(
            id = "m1",
            engineId = EngineId.FAKE,
            displayName = "fake",
            location = ModelLocation.AppPrivate("fake"),
        )
        val handle = engine.load(model, InferenceConfig(maxNewTokens = 8))
        var tokens = 0
        var done = false
        engine.generate(
            handle,
            GenerateRequest(
                listOf(ChatMessage(ChatRole.USER, "hi")),
                InferenceConfig(maxNewTokens = 8),
            ),
        ) { event ->
            when (event) {
                is EngineEvent.Token -> tokens++
                is EngineEvent.Done -> done = true
                is EngineEvent.Error -> {}
            }
        }
        // FakeEngine runs async; poll briefly.
        val deadline = System.currentTimeMillis() + 5000
        while (!done && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(done)
        assertTrue(tokens == 8)
        engine.unload(handle)
    }
}

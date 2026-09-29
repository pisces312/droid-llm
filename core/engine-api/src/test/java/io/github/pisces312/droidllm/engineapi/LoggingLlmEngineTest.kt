package io.github.pisces312.droidllm.engineapi

import io.github.pisces312.droidllm.engineapi.fake.FakeEngine
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decorator sits on the path of every real inference call, so the two
 * things worth pinning down are: it changes nothing observable to callers, and
 * it cannot throw even when the sink does.
 */
class LoggingLlmEngineTest {

    private class RecordingSink : EngineLogSink {
        val lines = CopyOnWriteArrayList<String>()
        override fun d(tag: String, message: String) { lines += "D/$tag $message" }
        override fun i(tag: String, message: String) { lines += "I/$tag $message" }
        override fun w(tag: String, message: String, error: Throwable?) { lines += "W/$tag $message" }
        override fun e(tag: String, message: String, error: Throwable?) { lines += "E/$tag $message" }
    }

    /** A sink that explodes: logging must never be able to fail an inference. */
    private object ThrowingSink : EngineLogSink {
        override fun d(tag: String, message: String) = error("boom")
        override fun i(tag: String, message: String) = error("boom")
        override fun w(tag: String, message: String, error: Throwable?) = error("boom")
        override fun e(tag: String, message: String, error: Throwable?) = error("boom")
    }

    private fun model() = LocalModel(
        id = "m1",
        engineId = EngineId.FAKE,
        displayName = "fake",
        location = ModelLocation.AppPrivate("fake"),
    )

    private fun request() = GenerateRequest(
        messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "You are a helpful assistant."),
            ChatMessage(ChatRole.USER, "hi"),
        ),
        config = InferenceConfig(maxNewTokens = 4),
    )

    /**
     * [FakeEngine.generate] streams from a coroutine, so the assertions have to
     * wait for completion rather than run straight after the call returns.
     */
    private suspend fun awaitIdle(job: GenerateJob, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (job.isActive && System.currentTimeMillis() < deadline) delay(10)
    }

    @Test
    fun passesEventsThroughAndRecordsGenerate() = runBlocking {
        val sink = RecordingSink()
        val engine = LoggingLlmEngine(FakeEngine()) { sink }

        val handle = engine.load(model(), InferenceConfig(maxNewTokens = 4))
        var tokens = 0
        var done = false
        val job = engine.generate(handle, request()) { event ->
            when (event) {
                is EngineEvent.Token -> tokens++
                is EngineEvent.Done -> done = true
                is EngineEvent.Error -> Unit
            }
        }
        awaitIdle(job)

        assertTrue("token events must reach the caller unchanged", tokens > 0)
        assertTrue("done must still be delivered", done)

        val text = sink.lines.joinToString("\n")
        assertTrue("load must be recorded", text.contains("load start"))
        assertTrue("load completion must be recorded", text.contains("load ok"))
        assertTrue("generate start must be recorded", text.contains("generate start"))
        assertTrue("first token latency must be recorded", text.contains("first token after"))
        assertTrue("completion summary must be recorded", text.contains("generate done"))
        // The prompt shape is the datum that exposed the short-prompt/EOS bug.
        assertTrue(
            "prompt shape (role:length) must be recorded",
            text.contains("promptChars=[system:") && text.contains("user:2"),
        )
    }

    @Test
    fun identityIsDelegated() {
        val engine = LoggingLlmEngine(FakeEngine()) { RecordingSink() }
        assertEquals(EngineId.FAKE, engine.id)
        assertEquals(FakeEngine().displayName, engine.displayName)
        assertFalse(engine.version.isKnown)
    }

    /** Adapter that declares its own defaults, like `LiteRtEngine` does. */
    private class RecommendingEngine : LlmEngine by FakeEngine() {
        override val defaults = EngineDefaults(
            temperature = 1.0f,
            topK = 64,
            topP = 0.95f,
            threads = 4,
            maxNewTokens = 4096,
            backend = Backend.CPU,
        )
    }

    @Test
    fun engineDefaultsAreDelegated() {
        // Hilt injects the decorator, never the adapter, so a missing forward here
        // would silently drop the adapter's own defaults and every model would run
        // on `InferenceConfig`'s placeholder values — which is how the LiteRT
        // greeting loop reached a real device (docs/litert.md §3.3).
        val declared = EngineDefaults(
            temperature = 1.0f,
            topK = 64,
            topP = 0.95f,
            threads = 4,
            maxNewTokens = 4096,
            backend = Backend.CPU,
        )
        // Guards the test itself: the adapter really does declare something else.
        assertEquals(declared, RecommendingEngine().defaults)
        assertEquals(
            declared,
            LoggingLlmEngine(RecommendingEngine()) { RecordingSink() }.defaults,
        )
    }

    @Test
    fun decoratorNeverInventsDefaults() {
        // The other direction: an adapter's values must pass through unchanged,
        // not be replaced by anything the decorator prefers.
        assertEquals(
            FakeEngine().defaults,
            LoggingLlmEngine(FakeEngine()) { RecordingSink() }.defaults,
        )
    }

    @Test
    fun survivesAThrowingSink() = runBlocking {
        // Guards the contract in DiagLogger's KDoc: diagnostics must never turn
        // a working generation into a crash.
        val engine = LoggingLlmEngine(FakeEngine()) { ThrowingSink }
        val handle = engine.load(model(), InferenceConfig(maxNewTokens = 4))
        var tokens = 0
        val job = engine.generate(handle, request()) { event ->
            if (event is EngineEvent.Token) tokens++
        }
        awaitIdle(job)
        assertTrue("generation must still run when the sink throws", tokens > 0)
    }

    @Test
    fun sinkIsResolvedPerCallNotAtWrapTime() {
        // Wrap before install must not freeze the decorator onto the no-op sink.
        val engine = LoggingLlmEngine(FakeEngine())
        val sink = RecordingSink()
        EngineLogging.install(sink)
        try {
            runBlocking {
                val handle = engine.load(model(), InferenceConfig(maxNewTokens = 4))
                engine.unload(handle)
            }
            assertTrue(
                "a sink installed after wrap must still be used",
                sink.lines.any { it.contains("load start") },
            )
        } finally {
            EngineLogging.install(NoopEngineLogSink)
        }
    }

    @Test
    fun noSinkByDefault() {
        // EngineLogging is wired in Application.onCreate; in a unit test it must
        // fall back to the no-op sink rather than NPE.
        EngineLogging.install(NoopEngineLogSink)
        assertEquals(NoopEngineLogSink, EngineLogging.current())
    }
}

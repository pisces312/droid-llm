package io.github.pisces312.droidllm.common.model

import io.github.pisces312.droidllm.engineapi.Backend
import io.github.pisces312.droidllm.engineapi.EngineDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigResolverTest {

    private val defaults = EngineDefaults(
        temperature = 0.7f,
        topK = 40,
        topP = 0.95f,
        threads = 4,
        maxNewTokens = 4096,
        backend = Backend.CPU,
    )

    @Test
    fun `no overlay falls back to engine defaults`() {
        val c = ConfigResolver.resolve(defaults)
        assertEquals(0.7f, c.temperature)
        assertEquals(40, c.topK)
        assertEquals(0.95f, c.topP)
        assertEquals(4, c.threads)
        assertEquals(4096, c.maxNewTokens)
        assertEquals(Backend.CPU, c.backend)
        assertNull(c.systemPrompt)
    }

    @Test
    fun `overlay wins over engine defaults field by field`() {
        val c = ConfigResolver.resolve(
            defaults = defaults,
            overlay = ModelParamsOverride(
                temperature = 1.0f,
                topK = 64,
                maxNewTokens = 256,
                backend = "GPU",
            ),
        )
        assertEquals(1.0f, c.temperature)
        assertEquals(64, c.topK)
        // untouched fields still inherit
        assertEquals(0.95f, c.topP)
        assertEquals(4, c.threads)
        assertEquals(256, c.maxNewTokens)
        assertEquals(Backend.GPU, c.backend)
    }

    @Test
    fun `maxNewTokensOverride replaces both overlay and default`() {
        val c = ConfigResolver.resolve(
            defaults = defaults,
            overlay = ModelParamsOverride(maxNewTokens = 256),
            maxNewTokensOverride = 16,
        )
        assertEquals(16, c.maxNewTokens)
    }

    @Test
    fun `systemPrompt prefers overlay then app prompt`() {
        val fromOverlay = ConfigResolver.resolve(
            defaults = defaults,
            overlay = ModelParamsOverride(systemPrompt = "pin"),
            appSystemPrompt = "app",
        )
        assertEquals("pin", fromOverlay.systemPrompt)

        val fromApp = ConfigResolver.resolve(
            defaults = defaults,
            appSystemPrompt = "app",
        )
        assertEquals("app", fromApp.systemPrompt)

        val blankApp = ConfigResolver.resolve(
            defaults = defaults,
            appSystemPrompt = "   ",
        )
        assertNull(blankApp.systemPrompt)
    }

    @Test
    fun `unknown backend string falls back to engine default`() {
        val c = ConfigResolver.resolve(
            defaults = defaults,
            overlay = ModelParamsOverride(backend = "NOT_A_BACKEND"),
        )
        assertEquals(Backend.CPU, c.backend)
    }

    @Test
    fun `null overlay is treated as empty`() {
        val withNull = ConfigResolver.resolve(defaults, overlay = null)
        val withEmpty = ConfigResolver.resolve(defaults, overlay = ModelParamsOverride())
        assertEquals(withEmpty, withNull.copy(seed = withEmpty.seed, enableThinking = withEmpty.enableThinking))
    }
}

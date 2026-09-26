package io.github.pisces312.droidllm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: load native libraries of all engines in one process, in multiple
 * orders, to catch symbol clashes early (DESIGN.md §6).
 *
 * P0: libraries do not exist yet. Each case is skipped until the engine's
 * so is packaged. Open one more case every time an engine lands (P1/P2/P3).
 */
@RunWith(AndroidJUnit4::class)
class EngineCoexistenceTest {

    private fun tryLoad(name: String): Boolean {
        return try {
            System.loadLibrary(name)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Test
    fun loadOrderA_litert_mnn_genie_llamacpp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.isNotEmpty())
        // P2: force-load LiteRT-LM AAR class (pulls its natives).
        assertTrue(touchLitertAar())
        // P1: llamacpp + mnn so exist
        assertTrue(tryLoad("llamacpp_chat_jni"))
        assertTrue(tryLoad("mnn_chat_jni"))
        // TODO(P3): assertTrue(tryLoad("genie_chat_jni"))
    }

    private fun touchLitertAar(): Boolean {
        return try {
            Class.forName("com.google.ai.edge.litertlm.Engine")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Test
    fun loadOrderB_llamacpp_mnn() {
        // P1: reverse order
        assertTrue(tryLoad("mnn_chat_jni"))
        assertTrue(tryLoad("llamacpp_chat_jni"))
    }

    @Test
    fun loadOrderC_all_available() {
        // P2 partial (litert + llamacpp + mnn); P3 adds genie
        assertTrue(touchLitertAar())
        assertTrue(tryLoad("llamacpp_chat_jni"))
        assertTrue(tryLoad("mnn_chat_jni"))
        // TODO(P3): genie
    }
}

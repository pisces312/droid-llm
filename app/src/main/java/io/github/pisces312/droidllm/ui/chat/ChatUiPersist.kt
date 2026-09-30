package io.github.pisces312.droidllm.ui.chat

import io.github.pisces312.droidllm.engineapi.GenerateJob
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.SessionHandle
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Chat runtime state that survives `Activity.recreate()` (language switch).
 *
 * A process singleton outside the ViewModel, because recreate tears the ViewModel
 * down while `engine.generate` callbacks may still be streaming into it. Without
 * this the user loses the transcript every time they pick a language in 设置.
 *
 * Deliberately **not** DI: it is lifecycle glue, not a service. Clearing the
 * app from recents kills the process and everything here with it.
 */
object ChatUiPersist {

    val messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val generating = MutableStateFlow(false)
    /** Plain string; re-localized by the next ViewModel when the UI language changes. */
    val status = MutableStateFlow("")
    val sessionState = MutableStateFlow(SessionState.IDLE)

    /** [io.github.pisces312.droidllm.engineapi.EngineId.name] of the last selected engine. */
    @Volatile
    var engineId: String? = null

    @Volatile
    var modelId: String? = null

    @Volatile
    var session: SessionHandle? = null

    @Volatile
    var sessionEngine: LlmEngine? = null

    /** Kept sessions when multi-model residency is on (DESIGN §3.3). */
    val resident = LinkedHashMap<String, Pair<LlmEngine, SessionHandle>>()

    @Volatile
    var generateSeq: Int = 0

    @Volatile
    var activeJob: GenerateJob? = null

    /** New-session / clear: drop the transcript but keep a loaded model. */
    fun clearConversation() {
        messages.value = emptyList()
    }

    /** Drop the live handle after unload; [resident] is left alone (multi-residency). */
    fun clearSession() {
        session = null
        sessionEngine = null
    }
}

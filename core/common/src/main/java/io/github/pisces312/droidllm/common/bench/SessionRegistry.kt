package io.github.pisces312.droidllm.common.bench

import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.SessionHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide registry of live [SessionHandle]s so benchmark can force-unload
 * every resident model before measuring RSS (DESIGN.md §2.1-5).
 */
@Singleton
class SessionRegistry @Inject constructor() {

    private data class Entry(
        val handle: SessionHandle,
        val engine: LlmEngine,
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    fun register(engine: LlmEngine, handle: SessionHandle) {
        synchronized(lock) {
            entries.removeAll { it.handle === handle }
            entries += Entry(handle, engine)
        }
    }

    fun unregister(handle: SessionHandle) {
        synchronized(lock) {
            entries.removeAll { it.handle === handle }
        }
    }

    /** Unload every registered session (best-effort; ignores individual failures). */
    suspend fun unloadAll() {
        val snapshot = synchronized(lock) {
            val copy = entries.toList()
            entries.clear()
            copy
        }
        for (e in snapshot) {
            runCatching { e.engine.unload(e.handle) }
        }
    }

    fun liveCount(): Int = synchronized(lock) { entries.size }
}

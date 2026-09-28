package io.github.pisces312.droidllm.apiserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight FIFO queue: LLM generate tasks run one at a time, in arrival
 * order. Mirrors MnnLlmChat's RequestQueueManager, simplified.
 */
class RequestQueueManager(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private data class QueuedTask(
        val requestId: String,
        val body: suspend () -> Any?,
        val completion: CompletableDeferred<Any?>,
    )

    private val channel = Channel<QueuedTask>(Channel.UNLIMITED)
    private val sendMutex = Mutex()

    init {
        scope.launch {
            for (task in channel) {
                try {
                    task.completion.complete(task.body())
                } catch (t: Throwable) {
                    task.completion.completeExceptionally(t)
                }
            }
        }
    }

    /** Submit [body] and suspend until it completes. Returns [body]'s result. */
    suspend fun <T> submit(requestId: String, body: suspend () -> T): T {
        val completion = CompletableDeferred<Any?>()
        sendMutex.withLock {
            channel.send(QueuedTask(requestId, body, completion))
        }
        @Suppress("UNCHECKED_CAST")
        return completion.await() as T
    }
}

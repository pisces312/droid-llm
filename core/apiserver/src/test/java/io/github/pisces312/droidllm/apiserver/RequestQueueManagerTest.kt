package io.github.pisces312.droidllm.apiserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RequestQueueManagerTest {

    @Test
    fun serializesTasksInOrder() = runBlocking {
        val queue = RequestQueueManager()
        val order = mutableListOf<Int>()
        val results = (1..5).map { i ->
            queue.submit("r$i") {
                order += i
                delay(5)
                i
            }
        }
        assertEquals(listOf(1, 2, 3, 4, 5), results)
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun singleFlightAtATime() = runBlocking {
        val queue = RequestQueueManager()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val jobs = (1..8).map { i ->
            async(Dispatchers.Default) {
                queue.submit("r$i") {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                    delay(10)
                    concurrent.decrementAndGet()
                    i
                }
            }
        }
        jobs.awaitAll()
        assertTrue("max concurrent was ${maxConcurrent.get()}", maxConcurrent.get() == 1)
    }

    @Test
    fun propagatesFailureWithoutStallingQueue() = runBlocking {
        val queue = RequestQueueManager()
        val failed = runCatching {
            withContext(Dispatchers.Default) {
                queue.submit("bad") { error("boom") }
            }
        }
        assertTrue(failed.isFailure)
        val ok = withContext(Dispatchers.Default) {
            queue.submit("good") { 42 }
        }
        assertEquals(42, ok)
    }
}

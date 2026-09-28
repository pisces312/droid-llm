package io.github.pisces312.droidllm.apiserver

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Lifecycle holder for the embedded Ktor server. One instance per process;
 * start/stop are idempotent.
 */
class ApiServer(
    private val configSource: ApiServerConfigSource,
    private val bridge: ApiInferenceBridge,
) {
    private val queue = RequestQueueManager()
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = server != null

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val config = kotlinx.coroutines.runBlocking { configSource.current() }
            val port = config.port
            if (!isPortAvailable(port)) {
                lastError = "port $port already in use"
                return false
            }
            server = embeddedServer(CIO, port = port, host = config.bindAddress) {
                apiModule(config, bridge, queue)
            }.start(wait = false)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            server = null
            false
        }
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        try {
            s.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        } catch (_: Exception) {
            // best-effort
        } finally {
            server = null
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                false // something is listening
            }
        } catch (_: Exception) {
            true
        }
    }
}

package io.github.pisces312.droidllm.common.diag

import io.github.pisces312.droidllm.engineapi.EngineLogSink

/**
 * Feeds [DiagLogger] from the engine layer.
 *
 * Kept in `core:common` (not `engine-api`) so the engine contract stays free of
 * app-side policy: `engine-api` only knows the [EngineLogSink] interface, and
 * the app decides that the destination is the in-app diagnostic buffer.
 */
class DiagEngineLogSink(private val logger: DiagLogger = DiagLogger) : EngineLogSink {
    override fun d(tag: String, message: String) = logger.d(tag, message)
    override fun i(tag: String, message: String) = logger.i(tag, message)
    override fun w(tag: String, message: String, error: Throwable?) = logger.w(tag, message, error)
    override fun e(tag: String, message: String, error: Throwable?) = logger.e(tag, message, error)
}

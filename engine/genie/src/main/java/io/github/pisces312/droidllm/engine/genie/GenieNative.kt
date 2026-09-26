package io.github.pisces312.droidllm.engine.genie

/**
 * JNI bindings for `libgenie_chat_jni.so` + prebuilt `libGenie.so` / QNN HTP libs.
 */
internal object GenieNative {
    init {
        System.loadLibrary("genie_chat_jni")
    }

    /** 0 on failure. [configJson] is a fully resolved Genie dialog config. */
    external fun nativeCreate(configJson: String): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeReset(handle: Long): Boolean
    external fun nativeSetMaxNumTokens(handle: Long, maxNumTokens: Int)

    /**
     * Blocking generate. Streams pieces via [callback].
     * [metricsOut] size >= 2: [0]=generatedPieceCount, [1]=status (0 ok, 1 empty, 2 query_failed).
     * Returns true when at least one piece was produced.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        callback: TokenCallback,
        metricsOut: LongArray,
    ): Boolean

    external fun nativeVersion(): String

    fun interface TokenCallback {
        fun onToken(text: String)
    }
}

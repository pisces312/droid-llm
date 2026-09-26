package io.github.pisces312.droidllm.engine.mnn

/**
 * JNI bindings for `libmnn_chat_jni.so` + prebuilt `libMNN.so`.
 */
internal object MnnNative {
    init {
        System.loadLibrary("mnn_chat_jni")
    }

    /** 0 on failure. */
    external fun nativeCreate(modelDir: String): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeSetConfig(handle: Long, configJson: String)
    external fun nativeReset(handle: Long)

    /**
     * Blocking generate. [messages] is [role, content, role, content, ...].
     * [metricsOut] must be size >= 5: promptTokens, generatedTokens, prefillUs, decodeUs, ttftUs.
     */
    external fun nativeGenerate(
        handle: Long,
        messages: Array<String>,
        maxNewTokens: Int,
        callback: TokenCallback,
        metricsOut: LongArray,
    )

    external fun nativeVersion(): String

    fun interface TokenCallback {
        fun onToken(text: String)
    }
}

package io.github.pisces312.droidllm.engine.llamacpp

/**
 * Thin JNI bindings for `libllamacpp_chat_jni.so`.
 * All natives operate on an opaque session handle produced by [nativeLoad].
 */
internal object LlamaCppNative {
    init {
        System.loadLibrary("llamacpp_chat_jni")
    }

    external fun nativeInit()
    external fun nativeSystemInfo(): String

    /** Returns 0 on failure. */
    external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeResetSampler(
        handle: Long,
        topK: Int,
        topP: Float,
        temp: Float,
        seed: Long,
    )

    /** Returns prompt token count, or throws / returns -1 on failure. */
    external fun nativePrefill(handle: Long, prompt: String, maxNewTokens: Int): Int

    /** Returns next UTF-8-complete piece, empty string for incomplete cache, null when finished. */
    external fun nativeNextToken(handle: Long): String?
    external fun nativeClearKv(handle: Long)
    external fun nativePromptTokens(handle: Long): Int
    external fun nativeGeneratedTokens(handle: Long): Int

    /** messages: [role, content, ...]. Uses the model's chat template. */
    external fun nativeApplyChatTemplate(handle: Long, messages: Array<String>): String
}

package io.github.pisces312.droidllm.engine.genie

import android.system.Os
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes the HTP "skel" library discoverable to adsprpc.
 *
 * `libQnnHtpV<arch>Skel.so` is *not* loaded by the app's linker: adsprpc (running
 * inside this process) has to find the file on the host and hand it to the CDSP.
 * Besides a fixed list of system paths (`/vendor/dsp/cdsp`, `/system/lib/rfsa/adsp`,
 * `/odm/firmware`, `./`, …) it also searches `$ADSP_LIBRARY_PATH`. Those system
 * paths contain no QNN skel on a retail device, and an untrusted app cannot read
 * `/vendor/dsp` anyway, so without this the DSP-side `dlopen_ex` fails and
 * `GenieDialog_create` returns null after ~300 ms:
 *
 * ```
 * open_mod_table_open_dynamic failed for file:///libQnnHtpV81Skel.so?…&_dom=cdsp
 * ```
 *
 * Pointing the variable at the app's own extracted native library dir works
 * because `:engine:genie` sets `useLegacyPackaging = true` (manifest
 * `extractNativeLibs=true`), so `lib/arm64-v8a/libQnnHtpV81Skel.so` really exists
 * as a readable file there. Mirrors chatapp_android `Conversation.java:60-62`.
 *
 * Deliberately a separate object: touching [GenieNative] initialises it and loads
 * `libgenie_chat_jni.so`, which must happen *after* the variables are set.
 */
internal object QnnEnv {
    private val applied = AtomicBoolean(false)

    /** Idempotent. [nativeLibDir] = `ApplicationInfo.nativeLibraryDir`. */
    fun ensure(nativeLibDir: String) {
        if (!applied.compareAndSet(false, true)) return
        Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, true)
        Os.setenv("LD_LIBRARY_PATH", nativeLibDir, true)
    }
}

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

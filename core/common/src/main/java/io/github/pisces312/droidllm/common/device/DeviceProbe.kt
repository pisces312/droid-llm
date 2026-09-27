package io.github.pisces312.droidllm.common.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pisces312.droidllm.engineapi.ProbeContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: ProbeContext? = null

    fun probe(): ProbeContext {
        cached?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dataDir = context.getExternalFilesDir(null) ?: context.filesDir
        val stat = StatFs(dataDir.absolutePath)
        val result = ProbeContext(
            socModel = readSocModel(),
            sdkInt = Build.VERSION.SDK_INT,
            hasOpenCl = libraryExists("libOpenCL.so") || File("/system/lib64/libOpenCL.so").exists(),
            hasCdspRpc = libraryExists("libcdsprpc.so") || File("/system/lib64/libcdsprpc.so").exists(),
            totalRamMb = mem.totalMem / (1024L * 1024L),
            availableStorageMb = stat.availableBytes / (1024L * 1024L),
        )
        cached = result
        return result
    }

    private fun readSocModel(): String? {
        return runCatching {
            Build::class.java.getField("SOC_MODEL").get(null) as? String
        }.getOrNull()
    }

    private fun libraryExists(name: String): Boolean {
        return runCatching {
            // Presence check without loading. /system/lib64 covers most devices.
            val paths = listOf(
                "/system/lib64/$name",
                "/vendor/lib64/$name",
                "/system/lib/$name",
            )
            paths.any { File(it).exists() }
        }.getOrDefault(false)
    }

    fun batteryTempC(): Float? {
        val intent = context.registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED,
        )) ?: return null
        val tenths = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    /**
     * Default shared model root: `files/models/`. Engines use named subfolders
     * under this root (`llamacpp/`, `mnn/`, …) when scanning.
     */
    fun defaultModelRoot(): File {
        val base = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        base.mkdirs()
        return base
    }

    fun externalStorageState(): String = Environment.getExternalStorageState()
}

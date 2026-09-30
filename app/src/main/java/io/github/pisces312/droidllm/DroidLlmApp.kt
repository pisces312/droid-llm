package io.github.pisces312.droidllm

import android.app.Application
import android.os.Process
import dagger.hilt.android.HiltAndroidApp
import io.github.pisces312.droidllm.common.diag.CrashReporter
import io.github.pisces312.droidllm.common.diag.DiagEngineLogSink
import io.github.pisces312.droidllm.common.diag.DiagLogger
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.engineapi.EngineLogging
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltAndroidApp
class DroidLlmApp : Application() {

    @Inject
    lateinit var settingsStore: AppSettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * No `attachBaseContext` override here any more. The UI language used to be applied
     * by wrapping Contexts, in this class and in the Activity; it is owned by AppCompat
     * now (`AppLanguageController`), which reapplies it before the first frame itself.
     * See docs/I18N.md §2.
     */
    override fun onCreate() {
        super.onCreate()
        // Diagnostic plumbing is wired before Hilt builds any engine so that a
        // cold-start load/generate is already recorded. The sink is a
        // process-wide singleton, so this needs no DI graph of its own.
        DiagLogger.attachStorage(this)
        DiagLogger.i(TAG, "app start pid=${Process.myPid()}")
        EngineLogging.install(DiagEngineLogSink(DiagLogger))
        // Armed before anything that can fail, and deliberately not gated on
        // the settings switch: the logcat section inside the report follows
        // that switch on its own, but a crash must never go unrecorded.
        CrashReporter.install(this)
        // Apply the persisted toggle now, not the first time the log screen is
        // opened. Without this, a user who turned logging off would still be
        // recorded for the whole session until they visited 设置 → 诊断.
        appScope.launch {
            runCatching { DiagLogger.setEnabled(settingsStore.current().diagnosticLogging) }
        }
    }

    private companion object {
        const val TAG = "app"
    }
}

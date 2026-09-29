package io.github.pisces312.droidllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.pisces312.droidllm.common.settings.AppLanguage
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.ThemeMode
import io.github.pisces312.droidllm.ui.DroidLlmRoot
import io.github.pisces312.droidllm.ui.theme.DroidLlmTheme
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: AppSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by remember {
                settingsStore.observe().map { it.themeMode }
            }.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            // Language changes while the app is running: DroidLlmApp only applies
            // the persisted value at process start, so picking one here has to be
            // pushed too. Runs on the main thread (see AppLocale).
            val language by remember {
                settingsStore.observe().map { it.language }
            }.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
            LaunchedEffect(language) {
                withContext(Dispatchers.Main) { AppLocale.apply(language) }
            }

            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // All-files-access is requested lazily when the user opts into a custom
            // model root (Settings → 数据 → 修改), not at first launch.
            DroidLlmTheme(darkTheme = dark) {
                DroidLlmRoot()
            }
        }
    }
}

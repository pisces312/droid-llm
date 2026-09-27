package io.github.pisces312.droidllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.ThemeMode
import io.github.pisces312.droidllm.ui.DroidLlmRoot
import io.github.pisces312.droidllm.ui.theme.DroidLlmTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.map

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

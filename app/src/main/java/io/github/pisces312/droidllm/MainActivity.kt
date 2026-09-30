package io.github.pisces312.droidllm

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.pisces312.droidllm.common.settings.AppLanguage
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.ThemeMode
import io.github.pisces312.droidllm.ui.DroidLlmRoot
import io.github.pisces312.droidllm.ui.theme.DroidLlmTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * `AppCompatActivity` rather than `ComponentActivity`, and that is the whole
 * dependency: the UI language is applied through
 * `AppCompatDelegate.setApplicationLocales` (see [AppLanguageController]), which is a
 * silent no-op without an installed AppCompat delegate. Nothing else relies on the base
 * class — the UI is pure Compose. The theme parent has to be an AppCompat theme to match.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsStore: AppSettingsStore

    @Inject
    lateinit var languageController: AppLanguageController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Carry a language picked before the app-locale move over to the new mechanism.
        // Deliberately here instead of `Application`: `getApplicationLocales()` can only
        // distinguish "nothing chosen" from "storage not attached yet" once a delegate
        // exists, i.e. after an Activity has been created. Idempotent either way, so it
        // costs nothing on the second and later recreations.
        lifecycleScope.launch {
            val legacy = runCatching { settingsStore.current().legacyLanguage }
                .getOrDefault(AppLanguage.SYSTEM)
            languageController.migrateLegacy(legacy)
        }

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

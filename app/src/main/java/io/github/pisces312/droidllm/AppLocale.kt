package io.github.pisces312.droidllm

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.pisces312.droidllm.common.settings.AppLanguage

/**
 * Applies the in-app UI language (设置 → 语言).
 *
 * One implementation, two call sites: [DroidLlmApp] pushes the persisted value on
 * every process start, and `MainActivity` reacts to the user changing it. Both go
 * through `AppCompatDelegate`'s per-app locale list, which is what makes
 * `stringResource` resolve against the chosen language — and, on Android 13+,
 * what surfaces the choice in the system's per-app language settings.
 *
 * [AppLanguage.SYSTEM] passes an **empty** list. That is how "follow the device"
 * is expressed; passing `Locale.getDefault()` would freeze whatever the device
 * language happened to be at that moment instead of tracking later changes.
 *
 * Must run on the main thread: when the value differs from what is currently
 * applied, AppCompat recreates running activities, which is exactly what makes an
 * already visible screen re-resolve its strings.
 */
object AppLocale {

    fun apply(language: AppLanguage) {
        val locales = language.tag?.let { LocaleListCompat.forLanguageTags(it) }
            ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

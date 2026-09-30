package io.github.pisces312.droidllm

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.pisces312.droidllm.common.settings.AppLanguage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the in-app UI language (设置 → 语言).
 *
 * The mechanism is AppCompat's app-locale API rather than a Context we wrap by hand.
 * `AppCompatDelegate` persists the choice, reapplies it before the first frame on cold
 * start, recreates the Activity for us, and on Android 13+ hands the value to the
 * system `LocaleManager` — which is also what puts the app under
 * Settings → Apps → droid-llm → Language. `res/xml/locale_config.xml` lists the two
 * languages it may offer, and the manifest opts into `autoStoreLocales` so Android 12
 * gets the same behaviour.
 *
 * One hard requirement: the host Activity must extend `AppCompatActivity`. With no
 * installed AppCompat delegate, `setApplicationLocales` is a **silent no-op** — it has
 * no Context to build the system `LocaleManager` from, so picking a language looks like
 * nothing happened. That failure mode is why this app used to wrap Contexts itself; the
 * cost of the official route is one base class and one theme parent, see docs/I18N.md §2.
 *
 * Nothing here re-applies the language on startup, and nothing should: on Android 13+
 * the OS both applies and persists the value, so a second copy in the app can only go
 * stale — a startup re-apply of a stale copy is a revert loop (`docs/I18N.md` §2).
 */
@Singleton
class AppLanguageController @Inject constructor() {

    /** The applied language; [AppLanguage.SYSTEM] means "no app-specific locale". */
    fun current(): AppLanguage = fromLocales(AppCompatDelegate.getApplicationLocales())

    /**
     * Apply and persist [language]. AppCompat recreates the Activity, which is how the
     * new locale reaches resources that were already inflated.
     *
     * No-op when unchanged: AppCompat would recreate the Activity regardless.
     */
    fun set(language: AppLanguage) {
        if (language == current()) return
        AppCompatDelegate.setApplicationLocales(language.toLocales())
    }
}

/** [AppLanguage.SYSTEM] is the *empty* list; that is what "follow the device" means. */
internal fun AppLanguage.toLocales(): LocaleListCompat =
    tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()

/** Inverse of [toLocales]. A tag outside `locale_config.xml` reads as [AppLanguage.SYSTEM]. */
internal fun fromLocales(locales: LocaleListCompat): AppLanguage =
    locales.get(0)?.language?.let { language ->
        AppLanguage.entries.firstOrNull { it.tag == language }
    } ?: AppLanguage.SYSTEM

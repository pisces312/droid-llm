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

    /**
     * One-time carry-over of a selection made before the app-locale move, so upgrading
     * users keep their language instead of dropping back to the device one.
     *
     * Call it where an AppCompat delegate already exists (i.e. from an Activity). Before
     * that, `getApplicationLocales()` cannot tell "nothing was ever chosen" from
     * "appcompat has not attached its storage yet" and could overwrite a newer choice.
     * Idempotent per process, and a no-op once any locale is set.
     *
     * @param legacy the value of `AppSettings.legacyLanguage`
     */
    fun migrateLegacy(legacy: AppLanguage) {
        if (migrationDone || legacy == AppLanguage.SYSTEM) return
        migrationDone = true
        // An empty list is the only case that means "this user has never picked a
        // language through AppCompat", i.e. the legacy value is the newer of the two.
        // If it is non-empty the user already chose under the new mechanism, and that
        // choice wins — overwriting it here would undo it on every launch.
        // (`LocaleListCompat` has no `isNotEmpty`, hence the negation.)
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        set(legacy)
    }

    @Volatile
    private var migrationDone = false
}

/** [AppLanguage.SYSTEM] is the *empty* list; that is what "follow the device" means. */
internal fun AppLanguage.toLocales(): LocaleListCompat =
    tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()

/** Inverse of [toLocales]. A tag outside `locale_config.xml` reads as [AppLanguage.SYSTEM]. */
internal fun fromLocales(locales: LocaleListCompat): AppLanguage =
    locales.get(0)?.language?.let { language ->
        AppLanguage.entries.firstOrNull { it.tag == language }
    } ?: AppLanguage.SYSTEM

package io.github.pisces312.droidllm

import android.view.ViewTreeObserver
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the "切到模型页画面持续闪烁、来回切几次退出" bug.
 *
 * What went wrong: `NavHost(startDestination = ...)` was handed a mutable global, and that
 * parameter is a key of the `remember` that builds the NavGraph. Switching language
 * recreates the Activity while the user sits on 设置, so the graph kept being rebuilt —
 * each rebuild tore down the back stack and restarted the enter/exit transition, which
 * recomposed again. Measured on the emulator: 26 frames / 4 s while idle (healthy: 0),
 * 5-7 GCs of ~25 MB per 4 s, PSS climbing until the process died.
 *
 * So the assertion is not about how the UI *looks* — it is "**nothing redraws while
 * nothing is happening**", measured the same way the bug was diagnosed: after a
 * `recreate()` on a non-start tab, count how many times the window actually draws.
 *
 * Calibration: healthy is 0-2 draws in 3 s; the runaway loop produced ~6.5 draws/s, i.e.
 * ~19 in the same window. [MAX_IDLE_DRAWS] sits between the two with margin on both sides
 * — raise it only with a measurement to back the change, never to make it pass.
 *
 * [drawCounterSeesForcedDraws] exists because an idle-draw assertion is worthless if the
 * counter is blind: it proves the listener is actually wired up.
 */
@RunWith(AndroidJUnit4::class)
class NavigationRecreateTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsTabSurvivesRecreateAndStaysIdle() {
        clickTab(R.string.nav_models)
        clickTab(R.string.nav_settings)
        rule.onNodeWithText(label(R.string.settings_language)).assertExists()

        // The step that used to start the loop: the user picks a language here, and
        // AppCompat recreates the Activity to apply it.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        // The selected tab comes back on its own: `rememberNavController()` saves and
        // restores the whole back stack. If this assertion starts failing, someone has
        // re-added a "remember the current tab" global — do not; see docs/I18N.md §2.
        rule.onNodeWithText(label(R.string.settings_language)).assertExists()

        val draws = countDraws(IDLE_WINDOW_MS)
        assertTrue(
            "Window redrew $draws times in ${IDLE_WINDOW_MS / 1000}s with no input " +
                "(limit $MAX_IDLE_DRAWS) — runaway recomposition is back; docs/I18N.md §2",
            draws <= MAX_IDLE_DRAWS,
        )
    }

    /** Positive control: an explicit invalidation must be seen, or the guard is blind. */
    @Test
    fun drawCounterSeesForcedDraws() {
        val forced = countDraws(CONTROL_WINDOW_MS) {
            rule.activity.window.decorView.invalidate()
        }
        assertTrue(
            "Counter saw 0 draws after an explicit invalidate() — it is not wired up, so " +
                "settingsTabSurvivesRecreateAndStaysIdle cannot fail either",
            forced > 0,
        )
    }

    /**
     * Matches the bottom-bar item, not the screen title: both render the same text, so the
     * search has to be narrowed to the clickable one.
     */
    private fun clickTab(resId: Int) {
        rule.onNode(hasText(label(resId)) and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    /** Label in the Activity's locale, which is not necessarily the device's. */
    private fun label(resId: Int): String = rule.activity.getString(resId)

    /**
     * Draw passes of the test window during [windowMs], with no input injected unless
     * [kick] asks for it.
     *
     * An `OnDrawListener` on the shared `ViewTreeObserver` counts real draws rather than
     * vsyncs — a self-posted frame callback would tick at 60/s no matter what the app does,
     * which is precisely the signal that has to be excluded.
     */
    private fun countDraws(windowMs: Long, kick: (() -> Unit)? = null): Int {
        val draws = AtomicInteger(0)
        val done = CountDownLatch(1)
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            val observer = decor.viewTreeObserver
            val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
            observer.addOnDrawListener(listener)
            decor.postDelayed(
                {
                    observer.removeOnDrawListener(listener)
                    done.countDown()
                },
                windowMs,
            )
            kick?.invoke()
        }
        done.await(windowMs + 5_000, TimeUnit.MILLISECONDS)
        return draws.get()
    }

    private companion object {
        const val IDLE_WINDOW_MS = 3_000L
        const val CONTROL_WINDOW_MS = 500L

        /**
         * Between the healthy ceiling (0-2 per 3 s) and the measured runaway (~19 per 3 s).
         * Raise it only with a measurement to back the change, never to make it pass.
         */
        const val MAX_IDLE_DRAWS = 10
    }
}

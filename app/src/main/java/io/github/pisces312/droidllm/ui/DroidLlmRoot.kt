package io.github.pisces312.droidllm.ui

import androidx.compose.ui.res.stringResource
import io.github.pisces312.droidllm.R

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.pisces312.droidllm.ui.benchmark.BenchmarkScreen
import io.github.pisces312.droidllm.ui.chat.ChatScreen
import io.github.pisces312.droidllm.ui.diag.LogScreen
import io.github.pisces312.droidllm.ui.models.ModelsScreen
import io.github.pisces312.droidllm.ui.settings.SettingsScreen

/** Every route in the app, in one place — the bar and the graph read the same constants. */
internal object AppRoutes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val BENCHMARK = "benchmark"
    const val SETTINGS = "settings"

    /** Pushed on top of the tabs; deliberately not a fifth tab. */
    const val LOGS = "logs"
}

/** `labelRes` rather than a literal: the tab bar is built at file scope. */
private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(AppRoutes.CHAT, R.string.nav_chat, Icons.Filled.Chat),
    Tab(AppRoutes.MODELS, R.string.nav_models, Icons.Filled.Folder),
    Tab(AppRoutes.BENCHMARK, R.string.nav_benchmark, Icons.Filled.BarChart),
    Tab(AppRoutes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun DroidLlmRoot() {
    // `rememberNavController()` is a `rememberSaveable` whose saver is
    // `NavController.saveState()` / `restoreState()` — the androidx KDoc calls it
    // "across config change and process death". The selected tab therefore survives a
    // language-switch `recreate()` on its own; do NOT bolt a global "current tab"
    // holder onto it. That is exactly what used to be here (`UiRoute`), and passing it
    // as `startDestination` rebuilt the NavGraph on every recomposition.
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: AppRoutes.CHAT

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { nav.switchTab(tab.route) },
                        icon = {
                            Icon(tab.icon, contentDescription = stringResource(tab.labelRes))
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val goToModels: () -> Unit = { nav.switchTab(AppRoutes.MODELS) }
        NavHost(
            navController = nav,
            // A compile-time constant, and that is load-bearing, not stylistic:
            // `startDestination` is one of the keys of the `remember` that builds the
            // NavGraph (androidx.navigation.compose.NavHost). Give it anything that can
            // change while the app runs and the graph is rebuilt - which tears down the
            // back stack and restarts the enter/exit transition - on every change,
            // indefinitely. See docs/I18N.md §2.
            startDestination = AppRoutes.CHAT,
            modifier = Modifier.padding(padding),
        ) {
            composable(AppRoutes.CHAT) { ChatScreen(onGoToModels = goToModels) }
            composable(AppRoutes.MODELS) { ModelsScreen() }
            composable(AppRoutes.BENCHMARK) { BenchmarkScreen(onGoToModels = goToModels) }
            composable(AppRoutes.SETTINGS) {
                SettingsScreen(onOpenLogs = { nav.navigate(AppRoutes.LOGS) })
            }
            // Pushed on top of the tabs rather than added as a fifth tab: logs
            // are opened to reproduce a bug, not browsed habitually.
            composable(AppRoutes.LOGS) { LogScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/**
 * Tab-to-tab navigation: one entry per tab, never a growing stack.
 *
 * `popUpTo` targets the graph's own start destination instead of the `"chat"` literal.
 * The literal used to look equivalent, but it silently stopped matching as soon as
 * something changed the NavHost's start destination: `chat` was no longer on the back
 * stack, `popUpTo` became a no-op (no exception, no log) and the stack grew on every
 * tab switch.
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

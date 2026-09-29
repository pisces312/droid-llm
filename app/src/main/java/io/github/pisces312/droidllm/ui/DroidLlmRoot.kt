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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.pisces312.droidllm.ui.benchmark.BenchmarkScreen
import io.github.pisces312.droidllm.ui.chat.ChatScreen
import io.github.pisces312.droidllm.ui.diag.LogScreen
import io.github.pisces312.droidllm.ui.models.ModelsScreen
import io.github.pisces312.droidllm.ui.settings.SettingsScreen

/** `labelRes` rather than a literal: the tab bar is built at file scope. */
private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("chat", R.string.nav_chat, Icons.Filled.Chat),
    Tab("models", R.string.nav_models, Icons.Filled.Folder),
    Tab("benchmark", R.string.nav_benchmark, Icons.Filled.BarChart),
    Tab("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun DroidLlmRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "chat"

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo("chat") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(tab.icon, contentDescription = stringResource(tab.labelRes))
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val goToModels: () -> Unit = {
            nav.navigate("models") {
                popUpTo("chat") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        NavHost(
            navController = nav,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") { ChatScreen(onGoToModels = goToModels) }
            composable("models") { ModelsScreen() }
            composable("benchmark") { BenchmarkScreen(onGoToModels = goToModels) }
            composable("settings") { SettingsScreen(onOpenLogs = { nav.navigate("logs") }) }
            // Pushed on top of the tabs rather than added as a fifth tab: logs
            // are opened to reproduce a bug, not browsed habitually.
            composable("logs") { LogScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

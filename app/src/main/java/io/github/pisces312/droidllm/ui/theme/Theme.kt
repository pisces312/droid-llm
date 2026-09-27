package io.github.pisces312.droidllm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DroidColors.DarkPrimary,
    onPrimary = DroidColors.DarkOnPrimary,
    primaryContainer = DroidColors.DarkSurfaceHigh,
    onPrimaryContainer = DroidColors.DarkTextPrimary,
    background = DroidColors.DarkBg,
    onBackground = DroidColors.DarkTextPrimary,
    surface = DroidColors.DarkSurface,
    onSurface = DroidColors.DarkTextPrimary,
    surfaceVariant = DroidColors.DarkSurfaceHigh,
    onSurfaceVariant = DroidColors.DarkTextSecondary,
    surfaceContainerLow = DroidColors.DarkSurface,
    surfaceContainer = DroidColors.DarkSurface,
    surfaceContainerHigh = DroidColors.DarkSurfaceHigh,
    surfaceContainerHighest = DroidColors.DarkSurfaceHigh,
    outline = DroidColors.DarkOutline,
    outlineVariant = DroidColors.DarkOutlineVariant,
    error = DroidColors.DarkError,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = DroidColors.LightPrimary,
    onPrimary = DroidColors.LightOnPrimary,
    primaryContainer = DroidColors.LightSurfaceHigh,
    onPrimaryContainer = DroidColors.LightTextPrimary,
    background = DroidColors.LightBg,
    onBackground = DroidColors.LightTextPrimary,
    surface = DroidColors.LightSurface,
    onSurface = DroidColors.LightTextPrimary,
    surfaceVariant = DroidColors.LightSurfaceHigh,
    onSurfaceVariant = DroidColors.LightTextSecondary,
    surfaceContainerLow = DroidColors.LightBg,
    surfaceContainer = DroidColors.LightSurface,
    surfaceContainerHigh = DroidColors.LightSurfaceHigh,
    surfaceContainerHighest = DroidColors.LightSurfaceHigh,
    outline = DroidColors.LightOutline,
    outlineVariant = DroidColors.LightOutlineVariant,
    error = DroidColors.LightError,
    onError = Color.White,
)

val LocalDroidExtraColors = staticCompositionLocalOf { DarkExtraColors }

object DroidTheme {
    val extra: DroidExtraColors
        @Composable @ReadOnlyComposable get() = LocalDroidExtraColors.current
}

/**
 * App theme. [darkTheme] null = follow system (UI_DESIGN.md §2 / Settings 外观).
 */
@Composable
fun DroidLlmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extras = if (darkTheme) DarkExtraColors else LightExtraColors
    CompositionLocalProvider(LocalDroidExtraColors provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = DroidTypography,
            content = content,
        )
    }
}

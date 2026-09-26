package io.github.pisces312.droidllm.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color tokens from UI_DESIGN.md §2. Prefer these over raw Material slots
 * for semantic roles (Accent / Warn / Ok).
 */
object DroidColors {
    // Dark (default)
    val DarkBg = Color(0xFF121212)
    val DarkSurface = Color(0xFF1E1E1E)
    val DarkSurfaceHigh = Color(0xFF2A2A2A)
    val DarkOutline = Color(0xFF3C3C3C)
    val DarkPrimary = Color(0xFF7C6AF5)
    val DarkOnPrimary = Color(0xFFFFFFFF)
    val DarkAccent = Color(0xFF26C6DA)
    val DarkWarn = Color(0xFFFFB74D)
    val DarkError = Color(0xFFEF5350)
    val DarkOk = Color(0xFF66BB6A)
    val DarkTextPrimary = Color(0xFFEDEDED)
    val DarkTextSecondary = Color(0xFF9E9E9E)
    val DarkTextDisabled = Color(0xFF616161)

    // Light
    val LightBg = Color(0xFFF6F5FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEEEAF8)
    val LightOutline = Color(0xFFC9C5D8)
    val LightPrimary = Color(0xFF5B4BD6)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightAccent = Color(0xFF00838F)
    val LightWarn = Color(0xFFB26A00)
    val LightError = Color(0xFFD32F2F)
    val LightOk = Color(0xFF2E7D32)
    val LightTextPrimary = Color(0xFF1C1B22)
    val LightTextSecondary = Color(0xFF5F5A6E)
    val LightTextDisabled = Color(0xFF9E98B0)
}

/** Semantic extras not fully covered by Material ColorScheme. */
data class DroidExtraColors(
    val accent: Color,
    val warn: Color,
    val ok: Color,
    val textDisabled: Color,
    val surfaceHigh: Color,
)

val DarkExtraColors = DroidExtraColors(
    accent = DroidColors.DarkAccent,
    warn = DroidColors.DarkWarn,
    ok = DroidColors.DarkOk,
    textDisabled = DroidColors.DarkTextDisabled,
    surfaceHigh = DroidColors.DarkSurfaceHigh,
)

val LightExtraColors = DroidExtraColors(
    accent = DroidColors.LightAccent,
    warn = DroidColors.LightWarn,
    ok = DroidColors.LightOk,
    textDisabled = DroidColors.LightTextDisabled,
    surfaceHigh = DroidColors.LightSurfaceHigh,
)

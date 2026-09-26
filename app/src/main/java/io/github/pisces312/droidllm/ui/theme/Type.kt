package io.github.pisces312.droidllm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale from UI_DESIGN.md §3. System font only.
 * Tabular numerals via SpanStyle.fontFeatureSettings when the platform supports it.
 */
private fun metricStyle(sizeSp: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = sizeSp.sp,
    fontWeight = weight,
).merge(SpanStyle(fontFeatureSettings = "tnum"))

val DroidTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 20.sp,
        fontWeight = FontWeight.W600,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        fontWeight = FontWeight.W600,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.W400,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.W400,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.W500,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.W500,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
    ),
)

/** Result-table key numbers. */
val MetricType: TextStyle = metricStyle(18, FontWeight.W700)

/** Inline tps / small metrics. */
val MetricSmallType: TextStyle = metricStyle(13, FontWeight.W600)

package com.muffuq.os.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.muffuq.os.R

object Palette {
    val Background = Color(0xFF07080C)
    val Panel = Color(0xF20E1018)
    val TextPrimary = Color(0xFFF4F5F8)
    val TextSecondary = Color(0xB3E6E9F0)
    val TextTertiary = Color(0x80E6E9F0)
    val Glass = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x24FFFFFF)
    val CardPlaceholder = Color(0xFF1A1D27)
    val Danger = Color(0xFFFF6B6B)
}

/** Selectable in the Customize panel; the index is what gets persisted. */
val AccentPresets = listOf(
    Color(0xFF8B7CFF),
    Color(0xFF38D9F5),
    Color(0xFFFF7A59),
    Color(0xFFB4F056),
    Color(0xFFFF7EC7),
    Color(0xFFFFC94D),
)

fun accentAt(index: Int): Color = AccentPresets.getOrElse(index) { AccentPresets.first() }

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private val MuffuqTypography = Typography().withFamily(Manrope)

@Composable
fun MuffuqTheme(accent: Color, content: @Composable () -> Unit) {
    val onAccent = if (accent.luminance() > 0.45f) Palette.Background else Color.White
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = Palette.Background,
            onBackground = Palette.TextPrimary,
            surface = Palette.CardPlaceholder,
            onSurface = Palette.TextPrimary,
            onSurfaceVariant = Palette.TextSecondary,
            border = accent,
        ),
        typography = MuffuqTypography,
        content = content,
    )
}

package io.github.rudolfhk.screentime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0A0B0E)
    val Surface = Color(0xFF121419)
    val SurfaceHigh = Color(0xFF1A1D24)
    val Outline = Color(0xFF242932)
    val Grid = Color(0xFF1E222A)
    val Text = Color(0xFFE8ECF2)
    val TextDim = Color(0xFF8A93A3)
    val TextFaint = Color(0xFF5A6270)
    val Accent = Color(0xFF4FD1FF)
    val AccentDim = Color(0xFF1C4B5E)
    val Violet = Color(0xFF9B7BFF)
    val Good = Color(0xFF5BE7A9)
    val Warn = Color(0xFFFFB454)
    val Bad = Color(0xFFFF6B7A)

    /** Distinct hues for categories / app shares. */
    val series = listOf(Accent, Violet, Good, Warn, Bad, Color(0xFF6E8BFF), Color(0xFFFF8FD1), Color(0xFFB9E86B), Color(0xFF7DE3E3), TextDim)
}

val Mono = FontFamily.Monospace

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Bg,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Violet,
    onSecondary = Palette.Bg,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Bg,
    outline = Palette.Outline,
    outlineVariant = Palette.Grid,
    error = Palette.Bad,
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp),
)

/** Small caps-style section label. */
val LabelStyle = TextStyle(fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, color = Palette.TextDim)

/** Tabular numbers. */
val NumberStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, color = Palette.Text)

@Composable
fun ScreenTimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

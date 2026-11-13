package io.github.rudolfhk.screentime.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark "instrument panel" palette: near-black neutrals with a single orange signal colour.
 * Secondary hues are only used where two series must be told apart.
 */
object Palette {
    val Bg = Color(0xFF0B0B0C)
    val Surface = Color(0xFF131315)
    val SurfaceHigh = Color(0xFF1B1B1E)
    val Outline = Color(0xFF29292D)
    val Grid = Color(0xFF1F1F23)
    val Text = Color(0xFFF2F1EE)
    val TextDim = Color(0xFF9B9A9F)
    val TextFaint = Color(0xFF606067)
    val Accent = Color(0xFFFF7A1A)
    val AccentBright = Color(0xFFFFA05C)
    val AccentDim = Color(0xFF3D2413)
    val Amber = Color(0xFFFFC247)
    val Steel = Color(0xFF7FA4D9)
    val Good = Color(0xFF4CC38A)
    val Warn = Color(0xFFFFC247)
    val Bad = Color(0xFFFF5A5F)

    /** Distinct hues for categories / app shares, orange first. */
    val series = listOf(
        Accent, Steel, Amber, Color(0xFFB98CFF), Good, Color(0xFFFF8FA3),
        Color(0xFF6FD3D3), Color(0xFFD9D26F), Color(0xFF9E7458), TextDim,
    )
}

/** Corner radii: deliberately tight for a technical look. */
object Radius {
    val Panel = 4.dp
    val Control = 3.dp
    val Bar = 1.dp
}

val Mono = FontFamily.Monospace

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Bg,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Steel,
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

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(Radius.Control),
    medium = RoundedCornerShape(Radius.Panel),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Mono, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp),
)

/** Uppercase section / metric label. */
val LabelStyle = TextStyle(fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim)

/** Tabular numbers. */
val NumberStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, color = Palette.Text)

/** Small monospace annotation (axis labels, hints with numbers). */
val MetaStyle = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = Palette.TextFaint)

@Composable
fun ScreenTimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

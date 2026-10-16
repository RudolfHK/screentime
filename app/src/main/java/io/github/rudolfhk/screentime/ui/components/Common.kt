package io.github.rudolfhk.screentime.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.Mono
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val CardShape = RoundedCornerShape(20.dp)

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (title != null) Text(title.uppercase(), style = LabelStyle, modifier = Modifier.weight(1f))
                    trailing?.invoke(this)
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** A labelled number with an optional comparison delta. */
@Composable
fun Kpi(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    delta: String? = null,
    /** True if an increase is bad (screen time, unlocks): colours the delta accordingly. */
    increaseIsBad: Boolean = true,
    sub: String? = null,
    valueColor: Color = Palette.Text,
) {
    Column(modifier) {
        Text(label.uppercase(), style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(value, style = NumberStyle.copy(fontSize = 22.sp, color = valueColor), maxLines = 1)
        if (delta != null) {
            val up = delta.startsWith("+")
            val down = delta.startsWith("−")
            val c = when {
                up -> if (increaseIsBad) Palette.Bad else Palette.Good
                down -> if (increaseIsBad) Palette.Good else Palette.Bad
                else -> Palette.TextDim
            }
            Text("$delta vs prev", style = NumberStyle.copy(fontSize = 11.sp, color = c, fontWeight = FontWeight.Normal))
        } else if (sub != null) {
            Text(sub, fontSize = 11.sp, color = Palette.TextDim, fontFamily = Mono, maxLines = 1)
        }
    }
}

/** Lays out KPIs in rows of [columns]. */
@Composable
fun KpiGrid(items: List<@Composable (Modifier) -> Unit>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun StatLine(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Palette.Text)
            if (hint != null) Text(hint, fontSize = 11.sp, color = Palette.TextFaint)
        }
        Text(value, style = NumberStyle.copy(fontSize = 15.sp))
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = if (selected) Palette.Accent.copy(alpha = 0.14f) else Color.Transparent
    val border = if (selected) Palette.Accent.copy(alpha = 0.6f) else Palette.Outline
    val fg = if (selected) Palette.Accent else Palette.TextDim
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp = 36.dp) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) { context.container.appInfo.icon(packageName) }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Text(packageName.substringAfterLast('.').take(1).uppercase(), color = Palette.TextDim, fontFamily = Mono)
        }
    }
}

/** Ranked row with a proportional bar underneath. */
@Composable
fun UsageRow(
    packageName: String,
    label: String,
    value: String,
    fraction: Float,
    sub: String?,
    color: Color = Palette.Accent,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(value, style = NumberStyle.copy(fontSize = 14.sp))
            }
            Spacer(Modifier.height(6.dp))
            ProportionBar(fraction, color)
            if (sub != null) {
                Spacer(Modifier.height(4.dp))
                Text(sub, fontSize = 11.sp, color = Palette.TextFaint, fontFamily = Mono, maxLines = 1)
            }
        }
    }
}

@Composable
fun ProportionBar(fraction: Float, color: Color = Palette.Accent, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Palette.Grid)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.TextFaint, fontSize = 13.sp, modifier = modifier.padding(vertical = 12.dp))
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
import io.github.rudolfhk.screentime.ui.theme.Mono
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import io.github.rudolfhk.screentime.ui.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val PanelShape = RoundedCornerShape(Radius.Panel)
val ControlShape = RoundedCornerShape(Radius.Control)

/** Standard horizontal gutter for all screens. */
val Gutter = 16.dp

// ---------------------------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------------------------

/** Screen title bar: title, optional subtitle and trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (leading != null) 4.dp else Gutter, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MetaStyle.copy(color = Palette.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions?.invoke(this)
    }
}

/** Bordered container with an accent-ticked header. */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
    ) {
        Column {
            if (title != null || trailing != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) {
                        Box(Modifier.size(width = 3.dp, height = 12.dp).background(Palette.Accent))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title.uppercase(), style = LabelStyle.copy(color = Palette.Text))
                            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Palette.TextFaint)
                        }
                    } else Spacer(Modifier.weight(1f))
                    trailing?.invoke(this)
                }
            }
            Column(Modifier.padding(contentPadding)) { content() }
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Palette.Grid)

// ---------------------------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------------------------

/** Signed change badge. [increaseIsBad] colours screen-time style metrics red when they grow. */
@Composable
fun DeltaBadge(delta: String, increaseIsBad: Boolean = true) {
    val up = delta.startsWith("+")
    val down = delta.startsWith("−")
    val c = when {
        up -> if (increaseIsBad) Palette.Bad else Palette.Good
        down -> if (increaseIsBad) Palette.Good else Palette.Bad
        else -> Palette.TextDim
    }
    val arrow = when {
        up -> "▲ "
        down -> "▼ "
        else -> ""
    }
    Text(
        arrow + delta.removePrefix("+").removePrefix("−"),
        style = NumberStyle.copy(fontSize = 10.sp, color = c, fontWeight = FontWeight.Medium),
        modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** A labelled number with optional comparison delta or caption. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    delta: String? = null,
    increaseIsBad: Boolean = true,
    caption: String? = null,
    valueColor: Color = Palette.Text,
    large: Boolean = false,
) {
    Column(modifier) {
        Text(label.uppercase(), style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(5.dp))
        Text(value, style = NumberStyle.copy(fontSize = if (large) 28.sp else 19.sp, color = valueColor), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        when {
            delta != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                DeltaBadge(delta, increaseIsBad)
                Spacer(Modifier.width(5.dp))
                Text("vs prev.", style = MetaStyle.copy(fontSize = 10.sp))
            }
            caption != null -> Text(caption, style = MetaStyle.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> Spacer(Modifier.height(14.dp))
        }
    }
}

/** Grid of metric cells separated by hairlines, like an instrument cluster. */
@Composable
fun MetricGrid(items: List<@Composable (Modifier) -> Unit>, columns: Int = 2) {
    Column {
        items.chunked(columns).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Divider()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, item ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Grid))
                    item(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Label / value row with optional hint, for lists of facts. */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueColor: Color = Palette.Text,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Palette.Text)
            if (hint != null) Text(hint, fontSize = 11.sp, color = Palette.TextFaint)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = NumberStyle.copy(fontSize = 14.sp, color = valueColor), textAlign = TextAlign.End)
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

/** Connected, rectangular segmented control. */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier
            .clip(ControlShape)
            .border(1.dp, Palette.Outline, ControlShape)
            .background(Palette.Surface)
            .height(IntrinsicSize.Min),
    ) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Outline))
            val isSel = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (isSel) Palette.Accent else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(vertical = if (compact) 7.dp else 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(),
                    style = LabelStyle.copy(color = if (isSel) Palette.Bg else Palette.TextDim, fontSize = if (compact) 10.sp else 11.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Underlined section tabs. */
@Composable
fun <T> SectionTabs(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            options.forEach { (value, label) ->
                val isSel = value == selected
                Column(
                    Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(value) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label.uppercase(),
                        style = LabelStyle.copy(color = if (isSel) Palette.Text else Palette.TextFaint, fontSize = 11.sp),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (isSel) Palette.Accent else Color.Transparent))
                }
            }
        }
        Divider()
    }
}

/** Rectangular filter chip. */
@Composable
fun FilterChip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val border = if (active) Palette.Accent else Palette.Outline
    val fg = if (active) Palette.Accent else Palette.TextDim
    Row(
        modifier
            .clip(ControlShape)
            .background(if (active) Palette.Accent.copy(alpha = 0.10f) else Palette.Surface)
            .border(1.dp, border, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Full-width primary action. [outlined] for the secondary/destructive variant. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when {
        !enabled -> Palette.SurfaceHigh
        outlined -> Color.Transparent
        else -> Palette.Accent
    }
    val fg = when {
        !enabled -> Palette.TextFaint
        outlined -> Palette.Text
        else -> Palette.Bg
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(ControlShape)
            .background(bg)
            .then(if (outlined) Modifier.border(1.dp, Palette.Outline, ControlShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = LabelStyle.copy(color = fg, fontSize = 13.sp, letterSpacing = 1.8.sp))
    }
}

/** Tappable list row with chevron (settings, navigation). */
@Composable
fun NavRow(
    label: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) Palette.Bad else Palette.TextDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = when {
                danger -> Palette.Bad
                enabled -> Palette.Text
                else -> Palette.TextFaint
            })
            if (hint != null) Text(hint, fontSize = 11.sp, color = Palette.TextFaint)
        }
        if (value != null) {
            Text(value, style = MetaStyle.copy(color = Palette.TextDim, fontSize = 12.sp))
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// App rows
// ---------------------------------------------------------------------------------------------

@Composable
fun AppIcon(packageName: String, size: Dp = 34.dp, label: String? = null) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) { context.container.appInfo.icon(packageName) }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh).border(1.dp, Palette.Outline, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Text((label ?: packageName.substringAfterLast('.')).take(1).uppercase(), color = Palette.TextDim, fontFamily = Mono, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Ranked app row: rank, icon, name, share bar, value and secondary metrics. */
@Composable
fun AppRankRow(
    rank: Int?,
    packageName: String,
    label: String,
    value: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    meta: String? = null,
    share: String? = null,
    color: Color = Palette.Accent,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(rank.toString().padStart(2, '0'), style = MetaStyle.copy(color = Palette.TextFaint), modifier = Modifier.width(24.dp))
        }
        AppIcon(packageName, label = label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(value, style = NumberStyle.copy(fontSize = 14.sp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProportionBar(fraction, color, Modifier.weight(1f), height = 3.dp)
                if (share != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(share, style = MetaStyle.copy(fontSize = 10.sp, color = Palette.TextDim), modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                }
            }
            if (meta != null) {
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MetaStyle.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ProportionBar(fraction: Float, color: Color = Palette.Accent, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Radius.Bar)).background(Palette.Grid)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(color),
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.TextFaint, fontSize = 13.sp, modifier = modifier.padding(vertical = 10.dp))
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(1.dp)).background(color))
}

@Composable
fun Legend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(c)
                Spacer(Modifier.width(6.dp))
                Text(l, style = MetaStyle.copy(fontSize = 10.sp, color = Palette.TextDim))
            }
        }
    }
}

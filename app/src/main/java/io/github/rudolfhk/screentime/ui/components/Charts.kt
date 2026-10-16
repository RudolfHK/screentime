package io.github.rudolfhk.screentime.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.ui.theme.Mono
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlin.math.max

/**
 * Vertical bar chart with tap-to-inspect. [goal] draws a dashed reference line.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Accent,
    goal: Long? = null,
    highlight: Int? = null,
) {
    if (values.isEmpty()) return
    var selected by remember(values.size) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Palette.TextFaint, fontFamily = Mono)
    val maxValue = max(values.max(), goal ?: 0L).coerceAtLeast(1L)
    val total = values.sum()
    val nonZero = values.count { it > 0 }

    Column(modifier) {
        val sel = selected
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (sel != null) format(values[sel]) else format(if (nonZero > 0) total / nonZero else 0),
                style = NumberStyle.copy(fontSize = 16.sp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (sel != null) labels.getOrElse(sel) { "" } else "avg per active bar · tap a bar",
                fontSize = 11.sp, color = Palette.TextDim,
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(values) {
                    detectTapGestures { pos ->
                        val slot = size.width / values.size.toFloat()
                        val i = (pos.x / slot).toInt().coerceIn(0, values.lastIndex)
                        selected = if (selected == i) null else i
                    }
                },
        ) {
            val labelH = 16.dp.toPx()
            val chartH = size.height - labelH
            val slot = size.width / values.size
            val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())

            // Grid
            for (g in 1..3) {
                val y = chartH * (1 - g / 3f)
                drawLine(Palette.Grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            drawLine(Palette.Outline, Offset(0f, chartH), Offset(size.width, chartH), strokeWidth = 1f)

            values.forEachIndexed { i, v ->
                val h = (v.toFloat() / maxValue) * (chartH - 4.dp.toPx())
                val x = i * slot + (slot - barW) / 2
                val isSel = selected == i
                val c = when {
                    isSel -> Palette.Text
                    highlight == i -> color
                    selected != null || highlight != null -> color.copy(alpha = 0.45f)
                    else -> color
                }
                if (v > 0) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(c, c.copy(alpha = c.alpha * 0.55f)), startY = chartH - h, endY = chartH),
                        topLeft = Offset(x, chartH - h),
                        size = Size(barW, h.coerceAtLeast(2f)),
                        cornerRadius = CornerRadius(barW / 3, barW / 3),
                    )
                }
            }

            if (goal != null && goal > 0) {
                val y = chartH - (goal.toFloat() / maxValue) * (chartH - 4.dp.toPx())
                drawLine(
                    Palette.Warn.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }

            // Sparse x labels (max ~7)
            val step = max(1, (values.size + 6) / 7)
            for (i in values.indices step step) {
                val text = labels.getOrElse(i) { "" }
                val layout = measurer.measure(text, labelStyle)
                val x = (i * slot + slot / 2 - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, chartH + 3.dp.toPx()))
            }
        }
    }
}

/** 7 × 24 heatmap: rows Monday..Sunday, columns hours. */
@Composable
fun Heatmap(matrix: Array<LongArray>, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = Palette.TextFaint, fontFamily = Mono)
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val maxV = matrix.maxOf { row -> row.max() }.coerceAtLeast(1L)
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        val left = 16.dp.toPx()
        val bottom = 16.dp.toPx()
        val gap = 2.dp.toPx()
        val cellW = (size.width - left) / 24f
        val cellH = (size.height - bottom) / 7f
        for (d in 0 until 7) {
            val l = measurer.measure(days[d], style)
            drawText(l, topLeft = Offset(0f, d * cellH + (cellH - l.size.height) / 2))
            for (h in 0 until 24) {
                val v = matrix[d][h]
                val t = (v.toFloat() / maxV)
                val c = if (v == 0L) Palette.Grid else lerp(Palette.AccentDim, color, t.coerceIn(0.08f, 1f))
                drawRoundRect(
                    c,
                    topLeft = Offset(left + h * cellW + gap / 2, d * cellH + gap / 2),
                    size = Size(cellW - gap, cellH - gap),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        for (h in listOf(0, 6, 12, 18)) {
            val l = measurer.measure(h.toString().padStart(2, '0'), style)
            drawText(l, topLeft = Offset(left + h * cellW, size.height - l.size.height))
        }
    }
}

/** 24h strip of sessions. [spans] are (start, end, unlocked) relative to [dayStart]. */
@Composable
fun DayTimeline(
    spans: List<Triple<Long, Long, Boolean>>,
    dayStart: Long,
    dayEnd: Long,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = Palette.TextFaint, fontFamily = Mono)
    Canvas(modifier.fillMaxWidth().height(52.dp)) {
        val trackH = 26.dp.toPx()
        val len = (dayEnd - dayStart).toFloat()
        drawRoundRect(Palette.Grid, size = Size(size.width, trackH), cornerRadius = CornerRadius(6.dp.toPx()))
        for ((s, e, unlocked) in spans) {
            val x0 = ((s - dayStart) / len).coerceIn(0f, 1f) * size.width
            val x1 = ((e - dayStart) / len).coerceIn(0f, 1f) * size.width
            drawRect(
                if (unlocked) Palette.Accent else Palette.TextFaint.copy(alpha = 0.6f),
                topLeft = Offset(x0, if (unlocked) 0f else trackH * 0.6f),
                size = Size((x1 - x0).coerceAtLeast(1.5f), if (unlocked) trackH else trackH * 0.4f),
            )
        }
        if (now in dayStart until dayEnd) {
            val x = ((now - dayStart) / len) * size.width
            drawLine(Palette.Text, Offset(x, -2.dp.toPx()), Offset(x, trackH + 2.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
        for (h in listOf(0, 6, 12, 18, 24)) {
            val text = h.toString().padStart(2, '0')
            val l = measurer.measure(text, style)
            val x = (h / 24f * size.width - l.size.width / 2).coerceIn(0f, size.width - l.size.width)
            drawText(l, topLeft = Offset(x, trackH + 6.dp.toPx()))
        }
    }
}

data class DistBar(val label: String, val value: Float, val text: String)

/** Horizontal bars with labels – used for distributions and categories. */
@Composable
fun HorizontalBars(bars: List<DistBar>, modifier: Modifier = Modifier, colors: List<Color> = listOf(Palette.Accent)) {
    val maxV = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bars.forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, fontSize = 12.sp, color = Palette.TextDim, fontFamily = Mono, modifier = Modifier.width(92.dp), maxLines = 1)
                Box(Modifier.weight(1f)) {
                    ProportionBar(b.value / maxV, colors[i % colors.size], height = 8.dp)
                }
                Text(b.text, fontSize = 12.sp, color = Palette.Text, fontFamily = Mono, modifier = Modifier.padding(start = 10.dp).width(64.dp), maxLines = 1)
            }
        }
    }
}

/** The big on/off control. */
@Composable
fun PowerToggle(enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    val on by animateFloatAsState(if (enabled) 1f else 0f, tween(500, easing = FastOutSlowInEasing), label = "on")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(
        modifier
            .size(176.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !busy, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(176.dp)) {
            val r = size.minDimension / 2
            // Pulse ring
            if (on > 0.01f) {
                drawCircle(Palette.Accent.copy(alpha = (1 - pulse) * 0.25f * on), radius = r * (0.78f + 0.22f * pulse), style = Stroke(2.dp.toPx()))
            }
            drawCircle(
                Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.18f * on), Color.Transparent), center = center, radius = r),
                radius = r,
            )
            drawCircle(Palette.Surface, radius = r * 0.74f)
            drawCircle(lerp(Palette.Outline, Palette.Accent, on), radius = r * 0.74f, style = Stroke(2.dp.toPx()))
            drawArc(
                Palette.Accent.copy(alpha = on),
                startAngle = -90f, sweepAngle = 360f * on, useCenter = false,
                topLeft = Offset(center.x - r * 0.86f, center.y - r * 0.86f), size = Size(r * 1.72f, r * 1.72f),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = if (enabled) "Stop tracking" else "Start tracking", tint = lerp(Palette.TextDim, Palette.Accent, on), modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(4.dp))
            Text(if (enabled) "ON" else "OFF", style = NumberStyle.copy(fontSize = 13.sp, color = lerp(Palette.TextDim, Palette.Accent, on), letterSpacing = 3.sp))
        }
    }
}

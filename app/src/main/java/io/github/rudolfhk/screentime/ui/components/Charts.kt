package io.github.rudolfhk.screentime.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlin.math.max

/**
 * Bar chart with y-axis scale, dashed average line, optional goal line, tap-to-inspect and a
 * min / avg / max / total footer.
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
    height: Int = 160,
    showFooter: Boolean = true,
    /** Labels for the footer's total cell (e.g. "TOTAL"), null hides it. */
    totalLabel: String? = "Total",
    detailLabels: List<String> = labels,
) {
    if (values.isEmpty()) return
    var selected by remember(values.size) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = MetaStyle.copy(fontSize = 9.sp)
    val maxValue = max(values.max(), goal ?: 0L).coerceAtLeast(1L)
    val active = values.filter { it > 0 }
    val avg = if (active.isNotEmpty()) active.sum() / active.size else 0L

    Column(modifier) {
        // Readout line: selected bar or hint.
        val sel = selected
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(22.dp)) {
            if (sel != null) {
                Text(detailLabels.getOrElse(sel) { "" }.uppercase(), style = LabelStyle)
                Spacer(Modifier.width(8.dp))
                Text(format(values[sel]), style = NumberStyle.copy(fontSize = 14.sp, color = Palette.Accent))
            } else {
                Text("TAP A BAR FOR DETAILS", style = LabelStyle.copy(color = Palette.TextFaint, fontSize = 9.sp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .pointerInput(values) {
                    detectTapGestures { pos ->
                        val axisW = 34.dp.toPx()
                        val slot = (size.width - axisW) / values.size.toFloat()
                        val i = ((pos.x - axisW) / slot).toInt().coerceIn(0, values.lastIndex)
                        selected = if (selected == i) null else i
                    }
                },
        ) {
            val axisW = 34.dp.toPx()
            val labelH = 16.dp.toPx()
            val chartH = size.height - labelH
            val plotW = size.width - axisW
            val slot = plotW / values.size
            val barW = (slot * 0.66f).coerceAtMost(26.dp.toPx()).coerceAtLeast(2f)
            fun y(v: Long) = chartH - (v.toFloat() / maxValue) * (chartH - 6.dp.toPx())

            // Grid + y-axis labels at 0, 50 %, 100 %.
            listOf(0L, maxValue / 2, maxValue).forEach { v ->
                val yy = y(v)
                drawLine(if (v == 0L) Palette.Outline else Palette.Grid, Offset(axisW, yy), Offset(size.width, yy), strokeWidth = 1f)
                if (v > 0) {
                    val l = measurer.measure(format(v), axisStyle)
                    drawText(l, topLeft = Offset(0f, (yy - l.size.height / 2).coerceAtLeast(0f)))
                }
            }

            values.forEachIndexed { i, v ->
                val x = axisW + i * slot + (slot - barW) / 2
                val c = when {
                    selected == i -> Palette.AccentBright
                    highlight == i -> color
                    selected != null -> color.copy(alpha = 0.35f)
                    highlight != null -> color.copy(alpha = 0.6f)
                    else -> color
                }
                if (v > 0) {
                    val top = y(v)
                    drawRoundRect(c, topLeft = Offset(x, top), size = Size(barW, (chartH - top).coerceAtLeast(2f)), cornerRadius = CornerRadius(1.dp.toPx()))
                } else {
                    drawRect(Palette.Grid, topLeft = Offset(x, chartH - 2f), size = Size(barW, 2f))
                }
            }

            if (avg > 0) {
                val yy = y(avg)
                drawLine(Palette.TextDim.copy(alpha = 0.7f), Offset(axisW, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            if (goal != null && goal > 0) {
                val yy = y(goal)
                drawLine(Palette.Amber, Offset(axisW, yy), Offset(size.width, yy), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f)))
            }

            // Sparse x labels (max ~8).
            val step = max(1, (values.size + 7) / 8)
            for (i in values.indices step step) {
                val text = labels.getOrElse(i) { "" }
                val layout = measurer.measure(text, axisStyle)
                val x = (axisW + i * slot + slot / 2 - layout.size.width / 2).coerceIn(axisW, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, chartH + 4.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend(
            buildList {
                add(Palette.TextDim to "avg ${format(avg)}")
                if (goal != null && goal > 0) add(Palette.Amber to "goal ${format(goal)}")
            },
        )
        if (showFooter) {
            Spacer(Modifier.height(10.dp))
            Divider()
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                FooterCell("Min", format(active.minOrNull() ?: 0), Modifier.weight(1f))
                FooterCell("Avg", format(avg), Modifier.weight(1f))
                FooterCell("Max", format(values.max()), Modifier.weight(1f))
                if (totalLabel != null) FooterCell(totalLabel, format(values.sum()), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FooterCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = LabelStyle.copy(fontSize = 9.sp, color = Palette.TextFaint))
        Text(value, style = NumberStyle.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 7 × 24 heatmap: rows Monday..Sunday, columns hours. */
@Composable
fun Heatmap(matrix: Array<LongArray>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MetaStyle.copy(fontSize = 9.sp)
    val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    val maxV = matrix.maxOf { row -> row.max() }.coerceAtLeast(1L)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(176.dp)) {
            val left = 30.dp.toPx()
            val bottom = 16.dp.toPx()
            val gap = 1.5.dp.toPx()
            val cellW = (size.width - left) / 24f
            val cellH = (size.height - bottom) / 7f
            for (d in 0 until 7) {
                val l = measurer.measure(days[d], style)
                drawText(l, topLeft = Offset(0f, d * cellH + (cellH - l.size.height) / 2))
                for (h in 0 until 24) {
                    val v = matrix[d][h]
                    val t = (v.toFloat() / maxV).coerceIn(0f, 1f)
                    val c = if (v == 0L) Palette.Grid else lerp(Palette.AccentDim, Palette.Accent, 0.15f + 0.85f * t)
                    drawRect(c, topLeft = Offset(left + h * cellW + gap / 2, d * cellH + gap / 2), size = Size(cellW - gap, cellH - gap))
                }
            }
            for (h in listOf(0, 6, 12, 18)) {
                val l = measurer.measure(h.toString().padStart(2, '0'), style)
                drawText(l, topLeft = Offset(left + h * cellW, size.height - l.size.height))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Scale
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LESS", style = LabelStyle.copy(fontSize = 9.sp, color = Palette.TextFaint))
            Spacer(Modifier.width(6.dp))
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
                Box(Modifier.size(width = 16.dp, height = 8.dp).background(lerp(Palette.AccentDim, Palette.Accent, 0.15f + 0.85f * t)))
                Spacer(Modifier.width(2.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("MORE", style = LabelStyle.copy(fontSize = 9.sp, color = Palette.TextFaint))
        }
    }
}

/** 24h strip of sessions. [spans] are (start, end, unlocked). */
@Composable
fun DayTimeline(
    spans: List<Triple<Long, Long, Boolean>>,
    dayStart: Long,
    dayEnd: Long,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = MetaStyle.copy(fontSize = 9.sp)
    Canvas(modifier.fillMaxWidth().height(54.dp)) {
        val trackH = 28.dp.toPx()
        val len = (dayEnd - dayStart).toFloat()
        drawRect(Palette.Grid, size = Size(size.width, trackH))
        // Hour ticks
        for (h in 1 until 24) {
            val x = h / 24f * size.width
            drawLine(Palette.Bg.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, trackH), strokeWidth = 1f)
        }
        for ((s, e, unlocked) in spans) {
            val x0 = ((s - dayStart) / len).coerceIn(0f, 1f) * size.width
            val x1 = ((e - dayStart) / len).coerceIn(0f, 1f) * size.width
            drawRect(
                if (unlocked) Palette.Accent else Palette.TextFaint,
                topLeft = Offset(x0, if (unlocked) 0f else trackH * 0.65f),
                size = Size((x1 - x0).coerceAtLeast(1.5f), if (unlocked) trackH else trackH * 0.35f),
            )
        }
        if (now in dayStart until dayEnd) {
            val x = ((now - dayStart) / len) * size.width
            drawLine(Palette.Text, Offset(x, -3.dp.toPx()), Offset(x, trackH + 3.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
        for (h in listOf(0, 6, 12, 18, 24)) {
            val l = measurer.measure(h.toString().padStart(2, '0'), style)
            val x = (h / 24f * size.width - l.size.width / 2).coerceIn(0f, size.width - l.size.width)
            drawText(l, topLeft = Offset(x, trackH + 6.dp.toPx()))
        }
    }
}

data class DistBar(val label: String, val value: Float, val text: String, val sub: String? = null)

/** Horizontal bars with labels – distributions, comparisons. */
@Composable
fun HorizontalBars(
    bars: List<DistBar>,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Palette.Accent),
    labelWidth: Int = 74,
    valueWidth: Int = 72,
) {
    val maxV = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        bars.forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.label, style = MetaStyle.copy(color = Palette.TextDim, fontSize = 11.sp), modifier = Modifier.width(labelWidth.dp), maxLines = 1)
                Box(Modifier.weight(1f)) { ProportionBar(b.value / maxV, colors[i % colors.size], height = 10.dp) }
                Column(Modifier.width(valueWidth.dp), horizontalAlignment = Alignment.End) {
                    Text(b.text, style = NumberStyle.copy(fontSize = 12.sp), maxLines = 1, textAlign = TextAlign.End)
                    if (b.sub != null) Text(b.sub, style = MetaStyle.copy(fontSize = 9.sp), maxLines = 1)
                }
            }
        }
    }
}

/** One horizontal bar split into coloured shares (category mix). */
@Composable
fun StackedShareBar(shares: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val total = shares.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Row(modifier.fillMaxWidth().height(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))) {
        shares.filter { it.first > 0 }.forEach { (v, c) ->
            Box(Modifier.weight(v / total).height(14.dp).background(c))
            Spacer(Modifier.width(1.dp))
        }
    }
}

/** Blinking square that signals live recording. */
@Composable
fun RecordingIndicator(active: Boolean, modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "rec",
    )
    StatusDot(if (active) Palette.Accent else Palette.TextFaint, modifier.alpha(if (active) blink else 1f), size = 9.dp)
}

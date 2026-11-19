package io.github.rudolfhk.screentime.ui.stats

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.core.Coverage
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.core.TimeRange
import io.github.rudolfhk.screentime.data.LogType
import io.github.rudolfhk.screentime.data.Settings
import io.github.rudolfhk.screentime.data.TrackingLogEntity
import io.github.rudolfhk.screentime.ui.components.DataRow
import io.github.rudolfhk.screentime.ui.components.Divider
import io.github.rudolfhk.screentime.ui.components.Legend
import io.github.rudolfhk.screentime.ui.components.Panel
import io.github.rudolfhk.screentime.ui.components.StatusDot
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val HOUR = 3_600_000L

/** Sync statistics derived from the tracking log. */
data class SyncHealth(
    val lastSync: Long?,
    val lastSource: String?,
    val syncs24h: Int,
    val background24h: Int,
    /** Median time between syncs in the last 24 h, or null with fewer than two. */
    val typicalInterval: Long?,
)

fun syncHealth(log: List<TrackingLogEntity>, now: Long): SyncHealth {
    val syncs = log.filter { it.type == LogType.SYNC }
    val recent = syncs.filter { it.ts >= now - 24 * HOUR }.sortedBy { it.ts }
    val intervals = recent.zipWithNext { a, b -> b.ts - a.ts }.sorted()
    return SyncHealth(
        lastSync = syncs.maxOfOrNull { it.ts },
        lastSource = syncs.maxByOrNull { it.ts }?.detail,
        syncs24h = recent.size,
        background24h = recent.count { it.detail == "background" },
        typicalInterval = intervals.getOrNull(intervals.size / 2),
    )
}

/**
 * How complete the numbers are. Coverage is the share of the period during which tracking was
 * switched on (from the start / stop log), minus spans the system log had already discarded.
 */
@Composable
fun CoveragePanel(
    coverage: Coverage,
    range: TimeRange,
    log: List<TrackingLogEntity>,
    settings: Settings,
    now: Long = System.currentTimeMillis(),
) {
    var showAllLog by remember { mutableStateOf(false) }
    val c = coverage
    val health = syncHealth(log, now)
    val ok = c.ratio >= 0.95
    Panel("Data quality", subtitle = "When tracking was on and how fresh the data is", contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.elapsedMs > 0) Format.percent(c.ratio) else "–",
                    style = NumberStyle.copy(fontSize = 24.sp, color = if (ok) Palette.Good else Palette.Warn),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("TRACKING COVERAGE", style = LabelStyle)
                    Text("tracking on for ${Format.duration(c.trackedMs)} of ${Format.duration(c.elapsedMs)}", style = MetaStyle)
                }
            }
            Spacer(Modifier.height(12.dp))
            CoverageBar(c, range, now)
            Spacer(Modifier.height(6.dp))
            Legend(listOf(Palette.Accent to "tracking on", Palette.TextFaint to "off", Palette.Bad to "data lost", Palette.Grid to "future"))
            Spacer(Modifier.height(10.dp))
            Text(
                "Android logs all phone use continuously. While tracking is on, the app copies that log " +
                    "every ${settings.syncIntervalMinutes} min and whenever you open it, so use in every app is " +
                    "counted – not only time spent in this app.",
                fontSize = 11.sp, color = Palette.TextFaint,
            )
        }
        Divider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            DataRow(
                "Last sync",
                health.lastSync?.let { DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString() } ?: "never",
                hint = health.lastSource?.let { "triggered by ${sourceLabel(it)}" },
                valueColor = if (settings.trackingEnabled && (health.lastSync == null || now - health.lastSync > 3 * HOUR)) Palette.Warn else Palette.Text,
            )
            Divider()
            DataRow(
                "Syncs in the last 24 h",
                "${health.syncs24h}",
                hint = "${health.background24h} in background" +
                    (health.typicalInterval?.let { " · typically every ${Format.duration(it)}" } ?: ""),
            )
            Divider()
            DataRow(
                "Data lost",
                if (c.dataGaps.isEmpty()) "none" else Format.duration(c.dataGaps.sumOf { it.end - it.start }),
                hint = if (c.dataGaps.isEmpty()) "No sync came too late for Android's log" else "${c.dataGaps.size} gap(s): no sync for several days",
                valueColor = if (c.dataGaps.isEmpty()) Palette.Good else Palette.Bad,
            )
        }
        val entries = log.filter { it.type != LogType.SYNC }
        if (entries.isNotEmpty()) {
            Divider()
            Text("TRACKING LOG", style = LabelStyle.copy(fontSize = 9.sp, color = Palette.TextFaint), modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
            val shown = if (showAllLog) entries else entries.take(6)
            shown.forEach { LogRow(it) }
            if (entries.size > 6) {
                Text(
                    if (showAllLog) "SHOW LESS" else "SHOW ALL ${entries.size} ENTRIES",
                    style = LabelStyle.copy(color = Palette.Accent),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { showAllLog = !showAllLog }.padding(vertical = 12.dp),
                )
            } else Spacer(Modifier.height(8.dp))
        }
    }
}

private fun sourceLabel(source: String) = when (source) {
    "background" -> "background schedule"
    "app" -> "opening the app"
    "start" -> "starting tracking"
    "stop" -> "stopping tracking"
    else -> source
}

@Composable
private fun LogRow(e: TrackingLogEntity) {
    val zone = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    fun t(ts: Long) = df.format(Instant.ofEpochMilli(ts).atZone(zone))
    val (color, title, detail) = when (e.type) {
        LogType.STARTED -> Triple(Palette.Good, "Tracking started", null)
        LogType.STOPPED -> Triple(Palette.TextDim, "Tracking stopped", null)
        LogType.BACKFILL -> Triple(Palette.Accent, "History backfilled", "Android still had data from ${t(e.value)}")
        LogType.GAP -> Triple(Palette.Bad, "Data lost: ${Format.duration(e.value)}", "No sync for days – the system log had expired")
        LogType.IMPORTED -> Triple(Palette.Steel, "Backup imported", "${e.value} new records")
        LogType.CLEARED -> Triple(Palette.Bad, "All data deleted", null)
        LogType.REPAIRED -> Triple(Palette.Amber, "Tracking start corrected", "Restored to ${t(e.value)} (fix for an older version)")
        else -> Triple(Palette.TextDim, e.type, null)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        StatusDot(color, Modifier.padding(top = 5.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Palette.Text)
            if (detail != null) Text(detail, style = MetaStyle.copy(fontSize = 10.sp))
        }
        Text(t(e.ts), style = MetaStyle.copy(fontSize = 10.sp, color = Palette.TextDim))
    }
}

/** The period as one strip: tracked, switched off, data lost, not yet happened. */
@Composable
private fun CoverageBar(c: Coverage, range: TimeRange, now: Long) {
    val measurer = rememberTextMeasurer()
    val style = MetaStyle.copy(fontSize = 9.sp)
    val zone = ZoneId.systemDefault()
    val long = range.duration > 2 * 86_400_000L
    val fmt = DateTimeFormatter.ofPattern(if (long) "d MMM" else "HH:mm")
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val barH = 14.dp.toPx()
        val len = range.duration.toFloat().coerceAtLeast(1f)
        fun x(ts: Long) = ((ts - range.start) / len).coerceIn(0f, 1f) * size.width
        fun span(s: Long, e: Long, color: Color) {
            val x0 = x(s)
            val x1 = x(e)
            if (x1 > x0) drawRect(color, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(1f), barH))
        }
        val elapsedEnd = minOf(range.end, now)
        span(range.start, range.end, Palette.Grid)
        span(range.start, elapsedEnd, Palette.Accent)
        c.untracked.forEach { span(it.start, it.end, Palette.TextFaint) }
        c.dataGaps.forEach { span(maxOf(it.start, range.start), minOf(it.end, elapsedEnd), Palette.Bad) }
        listOf(range.start, range.end).forEachIndexed { i, ts ->
            val l = measurer.measure(fmt.format(Instant.ofEpochMilli(ts).atZone(zone)), style)
            drawText(l, topLeft = Offset(if (i == 0) 0f else size.width - l.size.width, barH + 4.dp.toPx()))
        }
    }
}

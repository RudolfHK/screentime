package io.github.rudolfhk.screentime.ui.stats

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.core.Granularity
import io.github.rudolfhk.screentime.core.Stats
import io.github.rudolfhk.screentime.core.Summary
import io.github.rudolfhk.screentime.core.TimeRanges
import io.github.rudolfhk.screentime.ui.components.BarChart
import io.github.rudolfhk.screentime.ui.components.DistBar
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.Heatmap
import io.github.rudolfhk.screentime.ui.components.HorizontalBars
import io.github.rudolfhk.screentime.ui.components.Kpi
import io.github.rudolfhk.screentime.ui.components.KpiGrid
import io.github.rudolfhk.screentime.ui.components.Pill
import io.github.rudolfhk.screentime.ui.components.SectionCard
import io.github.rudolfhk.screentime.ui.components.StatLine
import io.github.rudolfhk.screentime.ui.components.StatusDot
import io.github.rudolfhk.screentime.ui.theme.Mono
import io.github.rudolfhk.screentime.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SummaryCard(stats: Stats, previous: Summary?, countNotifications: Boolean) {
    val s = stats.summary
    val p = previous
    SectionCard(if (stats.filtered) "Selected apps" else "Overview") {
        val items: List<@Composable (Modifier) -> Unit> = if (stats.filtered) listOf(
            { m -> Kpi("App time", Format.duration(s.appTime), m, delta = p?.let { Format.delta(s.appTime, it.appTime) }) },
            { m -> Kpi("Launches", s.appLaunches.toString(), m, delta = p?.let { Format.delta(s.appLaunches.toLong(), it.appLaunches.toLong()) }) },
            { m -> Kpi("Avg / day", Format.duration(s.avgPerDay), m, delta = p?.let { Format.delta(s.avgPerDay, it.avgPerDay) }) },
            { m -> Kpi("Avg session", Format.durationPrecise(if (s.appLaunches > 0) s.appTime / s.appLaunches else 0), m) },
            { m -> Kpi("Notifications", if (countNotifications) s.notifications.toString() else "off", m, delta = p?.takeIf { countNotifications }?.let { Format.delta(s.notifications.toLong(), it.notifications.toLong()) }) },
            { m -> Kpi("Share of screen", if (s.screenTime > 0) Format.percent(s.appTime.toDouble() / s.screenTime) else "–", m, sub = "of ${Format.duration(s.screenTime)}") },
        ) else listOf(
            { m -> Kpi("Screen time", Format.duration(s.screenTime), m, delta = p?.let { Format.delta(s.screenTime, it.screenTime) }) },
            { m -> Kpi("Avg / day", Format.duration(s.avgPerDay), m, delta = p?.let { Format.delta(s.avgPerDay, it.avgPerDay) }) },
            { m -> Kpi("Unlocks", s.unlocks.toString(), m, delta = p?.let { Format.delta(s.unlocks.toLong(), it.unlocks.toLong()) }) },
            { m -> Kpi("Avg session", Format.durationPrecise(s.avgSession), m, delta = p?.let { Format.delta(s.avgSession, it.avgSession) }) },
            { m -> Kpi("Glances", s.glances.toString(), m, sub = "of ${s.wakes} screen wakes") },
            { m -> Kpi("Notifications", if (countNotifications) s.notifications.toString() else "off", m, delta = p?.takeIf { countNotifications }?.let { Format.delta(s.notifications.toLong(), it.notifications.toLong()) }) },
            { m -> Kpi("Screen on", Format.duration(s.screenOnTime), m, sub = "incl. lock screen") },
            { m -> Kpi("App time", Format.duration(s.appTime), m, sub = "${s.appLaunches} launches") },
        )
        KpiGrid(items)
    }
}

@Composable
fun TrendCard(stats: Stats, goalMs: Long) {
    var showUnlocks by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    SectionCard(
        "Trend",
        trailing = {
            if (!stats.filtered) {
                Pill("Time", !showUnlocks) { showUnlocks = false }
                Spacer(Modifier.width(6.dp))
                Pill("Unlocks", showUnlocks) { showUnlocks = true }
            }
        },
    ) {
        val labels = stats.buckets.map { TimeRanges.bucketLabel(it.start, stats.granularity, zone) }
        val now = System.currentTimeMillis()
        val current = stats.buckets.indexOfFirst { now >= it.start && now < it.end }.takeIf { it >= 0 }
        if (showUnlocks && !stats.filtered) {
            BarChart(stats.buckets.map { it.unlocks.toLong() }, labels, { "$it unlocks" }, color = Palette.Violet, highlight = current)
        } else {
            BarChart(
                values = stats.buckets.map { if (stats.filtered) it.appTime else it.screenTime },
                labels = labels,
                format = Format::duration,
                goal = goalMs.takeIf { it > 0 && stats.granularity == Granularity.DAY && !stats.filtered },
                highlight = current,
            )
        }
        if (goalMs > 0 && !stats.filtered && stats.granularity != Granularity.HOUR) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Palette.Warn); Spacer(Modifier.width(6.dp))
                Text(
                    "Goal ${Format.duration(goalMs)}/day · exceeded on ${stats.daysOverGoal} of ${stats.days.size} days",
                    fontSize = 11.sp, color = Palette.TextDim, fontFamily = Mono,
                )
            }
        }
    }
}

@Composable
fun HeatmapCard(stats: Stats) {
    SectionCard("When – weekday × hour") {
        if (stats.heatmap.all { row -> row.all { it == 0L } }) EmptyHint("No usage in this period.")
        else Heatmap(stats.heatmap)
        Spacer(Modifier.height(8.dp))
        val peak = stats.hourly.indices.maxByOrNull { stats.hourly[it] }
        if (peak != null && stats.hourly[peak] > 0) {
            Text(
                "Peak hour ${peak.toString().padStart(2, '0')}:00 – ${Format.duration(stats.hourly[peak])} total",
                fontSize = 11.sp, color = Palette.TextDim, fontFamily = Mono,
            )
        }
    }
}

@Composable
fun HabitsCard(stats: Stats) {
    val zone = ZoneId.systemDefault()
    val tf = DateTimeFormatter.ofPattern("EEE HH:mm")
    SectionCard("Habits") {
        if (!stats.filtered) {
            StatLine("Avg first unlock", stats.avgFirstUseMinute?.let(Format::minuteOfDay) ?: "–", hint = "when your phone day starts")
            StatLine("Avg last use", stats.avgLastUseMinute?.let(Format::minuteOfDay) ?: "–", hint = "last screen-off after an unlock")
        }
        StatLine(
            "Night-time use", Format.duration(stats.nightTime),
            hint = "23:00 – 05:00 · ${if (stats.summary.screenTime > 0 && !stats.filtered) Format.percent(stats.nightTime.toDouble() / stats.summary.screenTime) + " of total" else "local time"}",
        )
        StatLine("Median session", Format.durationPrecise(stats.medianSession))
        StatLine("Longest session", Format.durationPrecise(stats.longestSession))
        StatLine("Median gap between uses", Format.durationPrecise(stats.medianGapBetweenUnlocks), hint = "screen-off → next unlock")
        StatLine(
            "Longest phone-free stretch",
            stats.longestBreak?.let { Format.duration(it.duration) } ?: "–",
            hint = stats.longestBreak?.let {
                tf.format(Instant.ofEpochMilli(it.start).atZone(zone)) + " → " + tf.format(Instant.ofEpochMilli(it.end).atZone(zone))
            },
        )
    }
}

@Composable
fun DistributionCard(stats: Stats) {
    SectionCard("Session lengths") {
        val total = stats.sessionDistribution.sumOf { it.count }
        if (total == 0) {
            EmptyHint("No sessions in this period.")
            return@SectionCard
        }
        HorizontalBars(
            stats.sessionDistribution.map { DistBar(it.label, it.count.toFloat(), "${it.count}") },
            colors = listOf(Palette.Violet),
        )
        Spacer(Modifier.height(10.dp))
        val short = stats.sessionDistribution.take(3).sumOf { it.count }
        Text(
            "${Format.percent(short.toDouble() / total)} of $total sessions were under a minute – quick checks.",
            fontSize = 11.sp, color = Palette.TextDim,
        )
    }
}

@Composable
fun CategoriesCard(stats: Stats, onSelectCategory: (Int) -> Unit) {
    if (stats.categories.isEmpty()) return
    SectionCard("Categories") {
        val total = stats.categories.sumOf { it.total }.coerceAtLeast(1)
        stats.categories.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Palette.series[i % Palette.series.size])
                Spacer(Modifier.width(10.dp))
                Text(
                    AppCategories.label(c.category),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Pill("${Format.duration(c.total)} · ${Format.percent(c.total.toDouble() / total)}", false) { onSelectCategory(c.category) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun CoverageCard(stats: Stats) {
    val c = stats.coverage
    val zone = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("d MMM HH:mm")
    SectionCard("Data quality") {
        StatLine("Tracking coverage", if (c.elapsedMs > 0) Format.percent(c.ratio) else "–", hint = "${Format.duration(c.trackedMs)} of ${Format.duration(c.elapsedMs)} tracked")
        if (c.dataGaps.isNotEmpty()) {
            Text("Gaps (system log expired before sync):", fontSize = 12.sp, color = Palette.Warn)
            c.dataGaps.take(5).forEach {
                Text(
                    "• " + df.format(Instant.ofEpochMilli(it.start).atZone(zone)) + " → " + df.format(Instant.ofEpochMilli(it.end).atZone(zone)),
                    fontSize = 11.sp, color = Palette.TextDim, fontFamily = Mono,
                )
            }
        }
        val untracked = c.untracked.filter { it.duration > MIN_UNTRACKED_MS }
        if (untracked.isNotEmpty()) {
            Text("Tracking was off for ${untracked.size} span(s), ${Format.duration(untracked.sumOf { it.duration })} total.", fontSize = 11.sp, color = Palette.TextDim)
        }
    }
}

private const val MIN_UNTRACKED_MS = 5 * 60_000L

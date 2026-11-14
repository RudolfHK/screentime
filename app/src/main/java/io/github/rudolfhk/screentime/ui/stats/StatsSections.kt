package io.github.rudolfhk.screentime.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.ui.components.StackedBarChart
import io.github.rudolfhk.screentime.ui.components.Sparkline
import io.github.rudolfhk.screentime.ui.components.DonutChart
import io.github.rudolfhk.screentime.ui.components.AppIcon
import io.github.rudolfhk.screentime.core.AppUsage
import androidx.compose.ui.text.style.TextOverflow
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.core.Granularity
import io.github.rudolfhk.screentime.core.Stats
import io.github.rudolfhk.screentime.core.Summary
import io.github.rudolfhk.screentime.core.TimeRanges
import io.github.rudolfhk.screentime.data.AppInfoEntity
import io.github.rudolfhk.screentime.ui.components.AppRankRow
import io.github.rudolfhk.screentime.ui.components.BarChart
import io.github.rudolfhk.screentime.ui.components.DataRow
import io.github.rudolfhk.screentime.ui.components.DeltaBadge
import io.github.rudolfhk.screentime.ui.components.DistBar
import io.github.rudolfhk.screentime.ui.components.Divider
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.Heatmap
import io.github.rudolfhk.screentime.ui.components.HorizontalBars
import io.github.rudolfhk.screentime.ui.components.MetricGrid
import io.github.rudolfhk.screentime.ui.components.MetricTile
import io.github.rudolfhk.screentime.ui.components.Panel
import io.github.rudolfhk.screentime.ui.components.ProportionBar
import io.github.rudolfhk.screentime.ui.components.SegmentedControl
import io.github.rudolfhk.screentime.ui.components.StackedShareBar
import io.github.rudolfhk.screentime.ui.components.StatusDot
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NoPadding = PaddingValues(0.dp)
private const val MIN_UNTRACKED_MS = 5 * 60_000L

// ---------------------------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------------------------

@Composable
fun SummaryPanel(stats: Stats, previous: Summary?, countNotifications: Boolean) {
    val s = stats.summary
    val p = previous
    val i = stats.insights
    val notif = if (countNotifications) s.notifications.toString() else "off"
    val notifDelta = p?.takeIf { countNotifications }?.let { Format.delta(s.notifications.toLong(), it.notifications.toLong()) }
    Panel(if (stats.filtered) "App summary" else "Summary", subtitle = if (p != null) "Change vs. previous period" else null, contentPadding = NoPadding) {
        val items: List<@Composable (Modifier) -> Unit> = if (stats.filtered) listOf(
            { m -> MetricTile("App time", Format.duration(s.appTime), m, delta = p?.let { Format.delta(s.appTime, it.appTime) }, large = true) },
            { m -> MetricTile("Avg / day", Format.duration(s.avgPerDay), m, delta = p?.let { Format.delta(s.avgPerDay, it.avgPerDay) }, large = true) },
            { m -> MetricTile("Launches", s.appLaunches.toString(), m, delta = p?.let { Format.delta(s.appLaunches.toLong(), it.appLaunches.toLong()) }) },
            { m -> MetricTile("Avg session", Format.durationPrecise(if (s.appLaunches > 0) s.appTime / s.appLaunches else 0), m) },
            { m -> MetricTile("Notifications", notif, m, delta = notifDelta) },
            { m -> MetricTile("Share of screen", if (s.screenTime > 0) Format.percent(s.appTime.toDouble() / s.screenTime) else "–", m, caption = "of ${Format.duration(s.screenTime)}") },
        ) else listOf(
            { m -> MetricTile("Screen time", Format.duration(s.screenTime), m, delta = p?.let { Format.delta(s.screenTime, it.screenTime) }, large = true) },
            { m -> MetricTile("Avg / day", Format.duration(s.avgPerDay), m, delta = p?.let { Format.delta(s.avgPerDay, it.avgPerDay) }, large = true) },
            { m -> MetricTile("Unlocks", s.unlocks.toString(), m, delta = p?.let { Format.delta(s.unlocks.toLong(), it.unlocks.toLong()) }) },
            { m -> MetricTile("Unlocks / day", "%.1f".format(i.avgUnlocksPerDay), m, caption = "${i.trackedDays} tracked days") },
            { m -> MetricTile("Avg session", Format.durationPrecise(s.avgSession), m, delta = p?.let { Format.delta(s.avgSession, it.avgSession) }) },
            { m -> MetricTile("Longest session", Format.durationPrecise(stats.longestSession), m, caption = "median ${Format.durationPrecise(stats.medianSession)}") },
            { m -> MetricTile("Glances", s.glances.toString(), m, caption = "of ${s.wakes} screen wakes") },
            { m -> MetricTile("Notifications", notif, m, delta = notifDelta) },
            { m -> MetricTile("Screen on", Format.duration(s.screenOnTime), m, caption = "incl. lock screen") },
            { m -> MetricTile("App time", Format.duration(s.appTime), m, caption = "${s.appLaunches} launches") },
        )
        MetricGrid(items)
    }
}

/** Short axis labels and long detail labels for the buckets of [stats]. */
fun bucketLabels(stats: Stats): Pair<List<String>, List<String>> {
    val zone = ZoneId.systemDefault()
    val short = stats.buckets.map { TimeRanges.bucketLabel(it.start, stats.granularity, zone) }
    val long = stats.buckets.map { b ->
        val z = Instant.ofEpochMilli(b.start).atZone(zone)
        when (stats.granularity) {
            Granularity.HOUR -> DateTimeFormatter.ofPattern("HH:00").format(z)
            Granularity.DAY -> DateTimeFormatter.ofPattern("EEE d MMM").format(z)
            Granularity.WEEK -> "Week of " + DateTimeFormatter.ofPattern("d MMM").format(z)
            Granularity.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy").format(z)
        }
    }
    return short to long
}

fun currentBucket(stats: Stats): Int? {
    val now = System.currentTimeMillis()
    return stats.buckets.indexOfFirst { now >= it.start && now < it.end }.takeIf { it >= 0 }
}

fun unitLabel(stats: Stats) = when (stats.granularity) {
    Granularity.HOUR -> "per hour"
    Granularity.DAY -> "per day"
    Granularity.WEEK -> "per week"
    Granularity.MONTH -> "per month"
}

@Composable
fun TrendPanel(stats: Stats, goalMs: Long) {
    var showCount by remember { mutableStateOf(false) }
    val countLabel = if (stats.filtered) "Opens" else "Unlocks"
    Panel(
        "Trend",
        subtitle = unitLabel(stats),
        trailing = {
            SegmentedControl(listOf(false to "Time", true to countLabel), showCount, { showCount = it }, Modifier.width(150.dp), compact = true)
        },
    ) {
        val (labels, detail) = bucketLabels(stats)
        val current = currentBucket(stats)
        if (showCount) {
            val counts = if (stats.filtered) {
                stats.buckets.indices.map { i -> stats.apps.sumOf { it.launchBuckets.getOrElse(i) { 0 } }.toLong() }
            } else stats.buckets.map { it.unlocks.toLong() }
            BarChart(counts, labels, { "$it" }, color = Palette.Steel, highlight = current, detailLabels = detail)
        } else {
            BarChart(
                values = stats.buckets.map { if (stats.filtered) it.appTime else it.screenTime },
                labels = labels,
                format = Format::duration,
                goal = goalMs.takeIf { it > 0 && stats.granularity == Granularity.DAY && !stats.filtered },
                highlight = current,
                detailLabels = detail,
            )
        }
        if (goalMs > 0 && !stats.filtered && stats.granularity != Granularity.HOUR) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Goal exceeded on ${stats.daysOverGoal} of ${stats.days.size} days",
                style = MetaStyle.copy(color = Palette.TextDim),
            )
        }
    }
}

/** Side-by-side table of this period vs. the previous one. */
@Composable
fun ComparisonPanel(stats: Stats, previous: Summary?) {
    if (previous == null) return
    val s = stats.summary
    data class Line(val label: String, val now: Long, val prev: Long, val fmt: (Long) -> String, val increaseIsBad: Boolean = true)
    val lines = if (stats.filtered) listOf(
        Line("App time", s.appTime, previous.appTime, Format::duration),
        Line("Launches", s.appLaunches.toLong(), previous.appLaunches.toLong(), { "$it" }),
        Line("Avg / day", s.avgPerDay, previous.avgPerDay, Format::duration),
        Line("Notifications", s.notifications.toLong(), previous.notifications.toLong(), { "$it" }),
    ) else listOf(
        Line("Screen time", s.screenTime, previous.screenTime, Format::duration),
        Line("Avg / day", s.avgPerDay, previous.avgPerDay, Format::duration),
        Line("Unlocks", s.unlocks.toLong(), previous.unlocks.toLong(), { "$it" }),
        Line("Avg session", s.avgSession, previous.avgSession, Format::durationPrecise),
        Line("Glances", s.glances.toLong(), previous.glances.toLong(), { "$it" }),
        Line("Notifications", s.notifications.toLong(), previous.notifications.toLong(), { "$it" }),
    )
    Panel("Period comparison", subtitle = "This period vs. the one before", contentPadding = NoPadding) {
        TableHeader(listOf("Metric" to 1.4f, "Now" to 1f, "Prev." to 1f, "Δ" to 0.9f))
        lines.forEachIndexed { idx, l ->
            if (idx > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.label, fontSize = 13.sp, color = Palette.Text, modifier = Modifier.weight(1.4f))
                Text(l.fmt(l.now), style = NumberStyle.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
                Text(l.fmt(l.prev), style = NumberStyle.copy(fontSize = 13.sp, color = Palette.TextDim), modifier = Modifier.weight(1f))
                Row(Modifier.weight(0.9f)) { Format.delta(l.now, l.prev)?.let { DeltaBadge(it, l.increaseIsBad) } ?: Text("–", style = MetaStyle) }
            }
        }
    }
}

@Composable
fun CoveragePanel(stats: Stats) {
    val c = stats.coverage
    val zone = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("d MMM HH:mm")
    Panel("Data quality", subtitle = "How complete these numbers are") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (c.elapsedMs > 0) Format.percent(c.ratio) else "–", style = NumberStyle.copy(fontSize = 22.sp, color = if (c.ratio >= 0.95) Palette.Good else Palette.Warn))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TRACKING COVERAGE", style = LabelStyle)
                Text("${Format.duration(c.trackedMs)} of ${Format.duration(c.elapsedMs)} recorded", style = MetaStyle)
            }
        }
        Spacer(Modifier.height(10.dp))
        ProportionBar(c.ratio.toFloat(), if (c.ratio >= 0.95) Palette.Good else Palette.Warn, height = 4.dp)
        val untracked = c.untracked.filter { it.duration > MIN_UNTRACKED_MS }
        if (untracked.isNotEmpty() || c.dataGaps.isNotEmpty()) Spacer(Modifier.height(10.dp))
        if (untracked.isNotEmpty()) {
            DataRow("Tracking off", Format.duration(untracked.sumOf { it.duration }), hint = "${untracked.size} span(s) in this period")
        }
        c.dataGaps.take(5).forEach {
            DataRow(
                "Data gap",
                Format.duration(it.end - it.start),
                hint = df.format(Instant.ofEpochMilli(it.start).atZone(zone)) + " → " + df.format(Instant.ofEpochMilli(it.end).atZone(zone)),
                valueColor = Palette.Warn,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Apps
// ---------------------------------------------------------------------------------------------

@Composable
fun CategoryPanel(stats: Stats, onSelectCategory: (Int) -> Unit) {
    if (stats.categories.isEmpty()) return
    val total = stats.categories.sumOf { it.total }.coerceAtLeast(1)
    val colorOf = { i: Int -> Palette.series[i % Palette.series.size] }
    Panel("Category mix", subtitle = "Tap a category to filter") {
        StackedShareBar(stats.categories.mapIndexed { i, c -> c.total.toFloat() to colorOf(i) })
        Spacer(Modifier.height(10.dp))
        stats.categories.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelectCategory(c.category) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(colorOf(i))
                Spacer(Modifier.width(10.dp))
                Text(AppCategories.label(c.category), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${c.apps} apps", style = MetaStyle, modifier = Modifier.width(56.dp))
                Text(Format.percent(c.total.toDouble() / total), style = MetaStyle.copy(color = Palette.TextDim), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                Text(Format.duration(c.total), style = NumberStyle.copy(fontSize = 13.sp), modifier = Modifier.width(70.dp), textAlign = TextAlign.End)
            }
        }
    }
}

enum class AppSort(val label: String) { TIME("Time"), LAUNCHES("Opens"), NOTIFICATIONS("Notif."), CHANGE("Δ") }

private const val TOP_N = 5

/** Overview: who gets the time – donut of shares plus per-app change vs. the previous period. */
@Composable
fun TopAppsPanel(
    stats: Stats,
    apps: Map<String, AppInfoEntity>,
    previousApps: Map<String, Long>,
    onOpenApp: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    if (stats.apps.isEmpty()) return
    val total = stats.apps.sumOf { it.total }.coerceAtLeast(1)
    val top = stats.apps.take(TOP_N)
    val other = total - top.sumOf { it.total }
    Panel(
        "Top apps",
        subtitle = "${stats.apps.size} apps used · ${Format.duration(total)}",
        trailing = {
            Text("ALL APPS →", style = LabelStyle.copy(color = Palette.Accent), modifier = Modifier.clickable(onClick = onSeeAll).padding(6.dp))
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                shares = top.mapIndexed { i, a -> a.total to Palette.series[i] } + (other to Palette.TextFaint),
                centerValue = Format.percent(top.sumOf { it.total }.toDouble() / total),
                centerLabel = "top $TOP_N",
                size = 128,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                top.forEachIndexed { i, a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenApp(a.packageName) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(Palette.series[i])
                        Spacer(Modifier.width(7.dp))
                        Text(apps[a.packageName]?.label ?: a.packageName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(Format.percent(a.total.toDouble() / total), style = MetaStyle.copy(fontSize = 10.sp), modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                    }
                }
                if (other > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Palette.TextFaint)
                        Spacer(Modifier.width(7.dp))
                        Text("Other ${stats.apps.size - top.size}", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.weight(1f))
                        Text(Format.percent(other.toDouble() / total), style = MetaStyle.copy(fontSize = 10.sp), modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Divider()
        top.forEachIndexed { i, a ->
            if (i > 0) Divider()
            Row(
                Modifier.fillMaxWidth().clickable { onOpenApp(a.packageName) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(a.packageName, 28.dp, label = apps[a.packageName]?.label)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(apps[a.packageName]?.label ?: a.packageName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.launches} opens · ⌀ ${Format.durationPrecise(a.avgSession)}", style = MetaStyle.copy(fontSize = 10.sp))
                }
                Sparkline(a.buckets, Modifier.width(56.dp), color = Palette.series[i])
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(66.dp)) {
                    Text(Format.duration(a.total), style = NumberStyle.copy(fontSize = 13.sp))
                    previousApps[a.packageName]?.let { Format.delta(a.total, it) }?.let { DeltaBadge(it) }
                        ?: Text(if (previousApps.isNotEmpty()) "new" else "", style = MetaStyle.copy(fontSize = 9.sp, color = Palette.Accent))
                }
            }
        }
    }
}

/** Stacked bars: how each top app contributes to every day / hour / week of the period. */
@Composable
fun AppTimelinePanel(stats: Stats, apps: Map<String, AppInfoEntity>) {
    if (stats.apps.isEmpty() || stats.buckets.size < 2) return
    val top = stats.apps.take(TOP_N)
    val n = stats.buckets.size
    val other = LongArray(n) { i -> stats.buckets[i].appTime - top.sumOf { it.buckets.getOrElse(i) { 0 } } }.map { it.coerceAtLeast(0) }.toLongArray()
    val series = top.map { (apps[it.packageName]?.label ?: it.packageName) to it.buckets } + ("Other apps" to other)
    val (labels, detail) = bucketLabels(stats)
    Panel("App usage over time", subtitle = "Top $TOP_N apps, ${unitLabel(stats)}") {
        StackedBarChart(
            series = series,
            colors = Palette.series.take(top.size) + Palette.TextFaint,
            labels = labels,
            detailLabels = detail,
            format = Format::duration,
        )
    }
}

@Composable
fun AppRankingPanel(
    stats: Stats,
    apps: Map<String, AppInfoEntity>,
    previousApps: Map<String, Long>,
    onOpenApp: (String) -> Unit,
) {
    var sort by remember { mutableStateOf(AppSort.TIME) }
    var showAll by remember { mutableStateOf(false) }
    val sorted = when (sort) {
        AppSort.TIME -> stats.apps
        AppSort.LAUNCHES -> stats.apps.sortedByDescending { it.launches }
        AppSort.NOTIFICATIONS -> stats.apps.sortedByDescending { it.notifications }
        AppSort.CHANGE -> stats.apps.sortedByDescending { it.total - (previousApps[it.packageName] ?: 0) }
    }
    val total = stats.apps.sumOf { it.total }.coerceAtLeast(1)
    Panel(
        "All apps",
        subtitle = "${stats.apps.size} apps · ${Format.duration(total)} total · tap for details",
        contentPadding = PaddingValues(top = 6.dp, bottom = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SORT", style = LabelStyle.copy(fontSize = 9.sp, color = Palette.TextFaint))
            Spacer(Modifier.width(10.dp))
            SegmentedControl(AppSort.entries.map { it to it.label }, sort, { sort = it }, Modifier.weight(1f), compact = true)
        }
        Spacer(Modifier.height(6.dp))
        if (sorted.isEmpty()) EmptyHint("No app usage in this period.", Modifier.padding(horizontal = 14.dp))
        val shown = if (showAll) sorted else sorted.take(20)
        shown.forEachIndexed { idx, a ->
            Divider()
            AppStatRow(
                rank = idx + 1,
                usage = a,
                label = apps[a.packageName]?.label ?: a.packageName,
                total = total,
                previous = previousApps[a.packageName],
                hasPrevious = previousApps.isNotEmpty(),
                onClick = { onOpenApp(a.packageName) },
            )
        }
        if (sorted.size > 20) {
            Divider()
            Text(
                if (showAll) "SHOW TOP 20" else "SHOW ALL ${sorted.size}",
                style = LabelStyle.copy(color = Palette.Accent),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 12.dp),
            )
        }
    }
}

/** One app with its own mini-dashboard: time, change, share, trend sparkline and key figures. */
@Composable
private fun AppStatRow(
    rank: Int,
    usage: AppUsage,
    label: String,
    total: Long,
    previous: Long?,
    hasPrevious: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rank.toString().padStart(2, '0'), style = MetaStyle.copy(color = Palette.TextFaint), modifier = Modifier.width(24.dp))
            AppIcon(usage.packageName, 34.dp, label = label)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(AppCategories.label(usage.category).uppercase(), style = LabelStyle.copy(fontSize = 8.sp, color = Palette.TextFaint))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.duration(usage.total), style = NumberStyle.copy(fontSize = 15.sp))
                when {
                    previous != null -> Format.delta(usage.total, previous)?.let { DeltaBadge(it) }
                    hasPrevious -> Text("NEW", style = LabelStyle.copy(fontSize = 9.sp, color = Palette.Accent))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProportionBar(usage.total.toFloat() / total, Palette.Accent, Modifier.weight(1f), height = 3.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.percent(usage.total.toDouble() / total), style = MetaStyle.copy(fontSize = 10.sp, color = Palette.TextDim), modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.width(12.dp))
            Sparkline(usage.buckets, Modifier.width(72.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(start = 24.dp)) {
            MiniStat("Opens", "${usage.launches}", Modifier.weight(1f))
            MiniStat("Avg", Format.durationPrecise(usage.avgSession), Modifier.weight(1f))
            MiniStat("Peak", usage.peakHour?.let { "${it.toString().padStart(2, '0')}h" } ?: "–", Modifier.weight(0.8f))
            MiniStat("Days", "${usage.activeDays}", Modifier.weight(0.7f))
            MiniStat("Notif.", "${usage.notifications}", Modifier.weight(0.8f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = LabelStyle.copy(fontSize = 8.sp, color = Palette.TextFaint))
        Text(value, style = NumberStyle.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1)
    }
}

/** App detail: facts about one app in the period. */
@Composable
fun AppFactsPanel(stats: Stats, countNotifications: Boolean) {
    val usage = stats.apps.firstOrNull() ?: return
    val zone = ZoneId.systemDefault()
    val tf = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    Panel("Usage profile", contentPadding = PaddingValues(0.dp)) {
        MetricGrid(
            listOf(
                { m -> MetricTile("Active days", "${usage.activeDays} / ${stats.days.size}", m, caption = "days with use") },
                { m -> MetricTile("Per active day", Format.duration(if (usage.activeDays > 0) usage.total / usage.activeDays else 0), m, caption = "average") },
                { m -> MetricTile("Median session", Format.durationPrecise(usage.medianSession), m, caption = "longest ${Format.durationPrecise(usage.longestSession)}") },
                { m -> MetricTile("Peak hour", usage.peakHour?.let { "${it.toString().padStart(2, '0')}:00" } ?: "–", m, caption = "most use") },
                { m -> MetricTile("Opens / day", "%.1f".format(if (stats.days.isNotEmpty()) usage.launches.toDouble() / stats.days.size else 0.0), m, caption = "${usage.launches} in total") },
                { m -> MetricTile("Notif. / open", if (countNotifications && usage.launches > 0) "%.1f".format(usage.notifications.toDouble() / usage.launches) else "–", m, caption = "${usage.notifications} received") },
            ),
        )
        Divider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            DataRow("First use in period", usage.firstUsed?.let { tf.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "–")
            Divider()
            DataRow("Last used", usage.lastUsed?.let { tf.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "–")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Patterns
// ---------------------------------------------------------------------------------------------

@Composable
fun HourlyPanel(stats: Stats) {
    val peak = stats.insights.peakHour
    Panel("Hour of day", subtitle = if (peak != null) "Peak at ${peak.toString().padStart(2, '0')}:00" else "Total use per hour") {
        BarChart(
            values = stats.hourly.toList(),
            labels = (0 until 24).map { it.toString().padStart(2, '0') },
            detailLabels = (0 until 24).map { "${it.toString().padStart(2, '0')}:00 – ${(it + 1).toString().padStart(2, '0')}:00" },
            format = Format::duration,
            highlight = peak,
            height = 130,
            totalLabel = null,
        )
        Spacer(Modifier.height(8.dp))
        DataRow(
            "Night-time use", Format.duration(stats.nightTime),
            hint = "23:00 – 05:00" + if (stats.summary.screenTime > 0 && !stats.filtered) " · ${Format.percent(stats.nightTime.toDouble() / stats.summary.screenTime)} of total" else "",
        )
    }
}

@Composable
fun HeatmapPanel(stats: Stats) {
    Panel("Weekly rhythm", subtitle = "Weekday × hour") {
        if (stats.heatmap.all { row -> row.all { it == 0L } }) EmptyHint("No usage in this period.")
        else Heatmap(stats.heatmap)
    }
}

@Composable
fun WeekdayPanel(stats: Stats) {
    val i = stats.insights
    if (stats.days.size < 2) return
    Panel("Weekday averages", subtitle = "Average per tracked day") {
        BarChart(
            values = i.weekdayAverages.toList(),
            labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            detailLabels = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
            format = Format::duration,
            color = Palette.Accent,
            height = 120,
            showFooter = false,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalBars(
            listOf(
                DistBar("Weekdays", i.weekdayAvg.toFloat(), Format.duration(i.weekdayAvg), "per day"),
                DistBar("Weekend", i.weekendAvg.toFloat(), Format.duration(i.weekendAvg), "per day"),
            ),
            colors = listOf(Palette.Accent, Palette.Steel),
        )
    }
}

@Composable
fun HabitsPanel(stats: Stats) {
    val zone = ZoneId.systemDefault()
    val tf = DateTimeFormatter.ofPattern("EEE HH:mm")
    Panel("Habits", contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        if (!stats.filtered) {
            DataRow("Avg first unlock", stats.avgFirstUseMinute?.let(Format::minuteOfDay) ?: "–", hint = "When your phone day starts")
            Divider()
            DataRow("Avg last use", stats.avgLastUseMinute?.let(Format::minuteOfDay) ?: "–", hint = "Last screen-off after an unlock")
            Divider()
        }
        DataRow("Median session", Format.durationPrecise(stats.medianSession))
        Divider()
        DataRow("Longest session", Format.durationPrecise(stats.longestSession))
        Divider()
        DataRow("Median gap between uses", Format.durationPrecise(stats.medianGapBetweenUnlocks), hint = "Screen-off → next unlock")
        Divider()
        DataRow(
            "Longest phone-free stretch",
            stats.longestBreak?.let { Format.duration(it.duration) } ?: "–",
            hint = stats.longestBreak?.let {
                tf.format(Instant.ofEpochMilli(it.start).atZone(zone)) + " → " + tf.format(Instant.ofEpochMilli(it.end).atZone(zone))
            },
        )
    }
}

@Composable
fun SessionLengthPanel(stats: Stats) {
    val total = stats.sessionDistribution.sumOf { it.count }
    Panel("Session lengths", subtitle = if (total > 0) "$total sessions" else null) {
        if (total == 0) {
            EmptyHint("No sessions in this period.")
            return@Panel
        }
        HorizontalBars(
            stats.sessionDistribution.map {
                DistBar(it.label, it.count.toFloat(), "${it.count}", Format.percent(it.count.toDouble() / total))
            },
            labelWidth = 64,
            valueWidth = 48,
        )
        Spacer(Modifier.height(12.dp))
        val short = stats.sessionDistribution.take(3).sumOf { it.count }
        val longTime = stats.sessionDistribution.drop(5).sumOf { it.total }
        val allTime = stats.sessionDistribution.sumOf { it.total }.coerceAtLeast(1)
        DataRow("Quick checks (< 1 min)", Format.percent(short.toDouble() / total), hint = "Share of sessions")
        DataRow("Long sessions (> 15 min)", Format.percent(longTime.toDouble() / allTime), hint = "Share of total time")
    }
}

@Composable
fun GoalPanel(stats: Stats, goalMs: Long) {
    if (goalMs <= 0 || stats.filtered) return
    val i = stats.insights
    Panel("Daily goal", subtitle = "Limit ${Format.duration(goalMs)} per day", contentPadding = NoPadding) {
        MetricGrid(
            listOf(
                { m -> MetricTile("Current streak", "${i.currentGoalStreak} d", m, caption = "days within goal", valueColor = if (i.currentGoalStreak > 0) Palette.Good else Palette.Text) },
                { m -> MetricTile("Best streak", "${i.bestGoalStreak} d", m, caption = "in this period") },
                { m -> MetricTile("Over goal", "${stats.daysOverGoal} d", m, caption = "of ${stats.days.size} days", valueColor = if (stats.daysOverGoal > 0) Palette.Bad else Palette.Text) },
                { m -> MetricTile("Avg vs goal", Format.percent(stats.summary.avgPerDay.toDouble() / goalMs), m, caption = "${Format.duration(stats.summary.avgPerDay)} / day") },
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Days
// ---------------------------------------------------------------------------------------------

@Composable
fun HighlightsPanel(stats: Stats) {
    val i = stats.insights
    val df = DateTimeFormatter.ofPattern("EEE d MMM")
    Panel("Highlights", contentPadding = NoPadding) {
        MetricGrid(
            listOf(
                { m -> MetricTile("Busiest day", i.busiestDay?.let { Format.duration(it.screenTime) } ?: "–", m, caption = i.busiestDay?.date?.let(df::format), valueColor = Palette.Accent) },
                { m -> MetricTile("Quietest day", i.quietestDay?.let { Format.duration(it.screenTime) } ?: "–", m, caption = i.quietestDay?.date?.let(df::format), valueColor = Palette.Good) },
                { m -> MetricTile("Tracked days", "${i.trackedDays}", m, caption = "in this period") },
                { m -> MetricTile("Avg / day", Format.duration(stats.summary.avgPerDay), m, caption = "%.1f unlocks / day".format(i.avgUnlocksPerDay)) },
            ),
        )
    }
}

@Composable
fun DailyLogPanel(stats: Stats, goalMs: Long, apps: Map<String, AppInfoEntity> = emptyMap()) {
    var showAll by remember { mutableStateOf(false) }
    val days = stats.days.reversed()
    val maxT = days.maxOfOrNull { it.screenTime }?.coerceAtLeast(1) ?: 1
    val df = DateTimeFormatter.ofPattern("EEE dd.MM")
    val countHeader = if (stats.filtered) "Opens" else "Unl."
    Panel("Daily log", subtitle = if (stats.filtered) "Newest first" else "Newest first · top apps per day", contentPadding = PaddingValues(bottom = 4.dp)) {
        TableHeader(listOf("Date" to 1.25f, "Time" to 1.15f, countHeader to 0.6f, "First" to 0.75f, "Last" to 0.75f))
        val shown = if (showAll) days else days.take(31)
        shown.forEachIndexed { idx, d ->
            if (idx > 0) Divider()
            val over = goalMs > 0 && !stats.filtered && d.screenTime > goalMs
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(df.format(d.date), style = MetaStyle.copy(color = Palette.Text, fontSize = 12.sp), modifier = Modifier.weight(1.25f))
                    Text(Format.duration(d.screenTime), style = NumberStyle.copy(fontSize = 13.sp, color = if (over) Palette.Bad else Palette.Text), modifier = Modifier.weight(1.15f))
                    Text("${if (stats.filtered) d.launches else d.unlocks}", style = NumberStyle.copy(fontSize = 12.sp, color = Palette.TextDim), modifier = Modifier.weight(0.6f))
                    Text(d.firstUseMinute?.let(Format::minuteOfDay) ?: "–", style = MetaStyle.copy(fontSize = 12.sp, color = Palette.TextDim), modifier = Modifier.weight(0.75f))
                    Text(d.lastUseMinute?.let(Format::minuteOfDay) ?: "–", style = MetaStyle.copy(fontSize = 12.sp, color = Palette.TextDim), modifier = Modifier.weight(0.75f))
                }
                Spacer(Modifier.height(5.dp))
                ProportionBar(d.screenTime.toFloat() / maxT, if (over) Palette.Bad else Palette.Accent, height = 2.dp)
                if (!stats.filtered && d.topApps.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        d.topApps.forEach { (pkg, t) ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(pkg, 16.dp, label = apps[pkg]?.label)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    apps[pkg]?.label ?: pkg.substringAfterLast('.'),
                                    style = MetaStyle.copy(fontSize = 10.sp, color = Palette.TextDim),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(Format.duration(t), style = NumberStyle.copy(fontSize = 10.sp, color = Palette.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), maxLines = 1)
                            }
                        }
                        repeat(3 - d.topApps.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (days.size > 31) {
            Divider()
            Text(
                if (showAll) "SHOW LAST 31 DAYS" else "SHOW ALL ${days.size} DAYS",
                style = LabelStyle.copy(color = Palette.Accent),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun TableHeader(columns: List<Pair<String, Float>>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        columns.forEach { (label, w) ->
            Text(label.uppercase(), style = LabelStyle.copy(fontSize = 9.sp, color = Palette.TextFaint), modifier = Modifier.weight(w))
        }
    }
    Divider()
}

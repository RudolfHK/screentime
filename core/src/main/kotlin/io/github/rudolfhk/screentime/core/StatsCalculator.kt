package io.github.rudolfhk.screentime.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class StatsInput(
    val screenSessions: List<ScreenSession>,
    val appSessions: List<AppSession>,
    val unlocks: List<Long>,
    val notifications: List<NotificationEvent>,
    val trackingPeriods: List<TrackingPeriod>,
    val gaps: List<DataGap>,
    val categories: Map<String, Int> = emptyMap(),
)

data class StatsQuery(
    val range: TimeRange,
    val zone: ZoneId,
    val now: Long,
    /** Only these packages, or null for all. */
    val packages: Set<String>? = null,
    /** Only this category (see [AppCategories]), or null for all. */
    val category: Int? = null,
    val excluded: Set<String> = emptySet(),
    val dailyGoalMs: Long = 0,
    val granularity: Granularity? = null,
)

/** Headline numbers, also computed for the previous period to show deltas. */
data class Summary(
    val screenTime: Long,
    val screenOnTime: Long,
    val unlocks: Int,
    val wakes: Int,
    val glances: Int,
    val notifications: Int,
    val avgSession: Long,
    val appTime: Long,
    val appLaunches: Int,
    /** Average screen time per tracked day. */
    val avgPerDay: Long,
)

data class AppUsage(
    val packageName: String,
    val total: Long,
    val launches: Int,
    val avgSession: Long,
    val longestSession: Long,
    val notifications: Int,
    val lastUsed: Long?,
    val category: Int,
)

data class CategoryUsage(val category: Int, val total: Long, val apps: Int)

data class Bucket(val start: Long, val end: Long, val screenTime: Long, val unlocks: Int, val appTime: Long)

data class DayHabit(
    val date: LocalDate,
    val screenTime: Long,
    val unlocks: Int,
    /** Minutes after local midnight of the first unlock, or null. */
    val firstUseMinute: Int?,
    /** Minutes after local midnight of the last screen-off following an unlock that day. */
    val lastUseMinute: Int?,
)

data class DistributionBin(val label: String, val maxMs: Long, val count: Int, val total: Long)

data class Coverage(
    /** Share of the elapsed part of the range that tracking was active (0..1). */
    val ratio: Double,
    val trackedMs: Long,
    val elapsedMs: Long,
    val untracked: List<TimeRange>,
    val dataGaps: List<DataGap>,
)

data class Stats(
    val range: TimeRange,
    val granularity: Granularity,
    val summary: Summary,
    val buckets: List<Bucket>,
    /** [weekday 0=Mon..6=Sun][hour] → screen time ms (or app time when filtered). */
    val heatmap: Array<LongArray>,
    val hourly: LongArray,
    val apps: List<AppUsage>,
    val categories: List<CategoryUsage>,
    val days: List<DayHabit>,
    val sessionDistribution: List<DistributionBin>,
    val medianSession: Long,
    val longestSession: Long,
    val medianGapBetweenUnlocks: Long,
    val longestBreak: TimeRange?,
    val nightTime: Long,
    val avgFirstUseMinute: Int?,
    val avgLastUseMinute: Int?,
    val daysOverGoal: Int,
    val coverage: Coverage,
    val filtered: Boolean,
)

object StatsCalculator {

    /** Night window used for "night-time usage": 23:00 – 05:00 local time. */
    val NIGHT_HOURS = setOf(23, 0, 1, 2, 3, 4)

    private val DIST_BINS = listOf(
        "<10s" to 10_000L,
        "10–30s" to 30_000L,
        "30s–1m" to 60_000L,
        "1–5m" to 5 * 60_000L,
        "5–15m" to 15 * 60_000L,
        "15–30m" to 30 * 60_000L,
        "30–60m" to 60 * 60_000L,
        ">1h" to Long.MAX_VALUE,
    )

    fun appIncluded(pkg: String, q: StatsQuery, categories: Map<String, Int>): Boolean {
        if (pkg in q.excluded) return false
        if (q.packages != null && pkg !in q.packages) return false
        if (q.category != null && (categories[pkg] ?: AppCategories.UNDEFINED) != q.category) return false
        return true
    }

    fun summarize(input: StatsInput, q: StatsQuery): Summary {
        val r = q.range
        var screenTime = 0L
        var screenOn = 0L
        var wakes = 0
        var glances = 0
        var unlockedSessions = 0
        for (s in input.screenSessions) {
            screenOn += overlap(s.start, s.end, r.start, r.end)
            s.unlockAt?.let { screenTime += overlap(it, s.end, r.start, r.end) }
            if (s.start in r) {
                wakes++
                if (s.unlockAt == null) glances++
            }
            if (s.unlockAt != null && s.unlockAt in r) unlockedSessions++
        }
        var appTime = 0L
        var launches = 0
        for (a in input.appSessions) {
            if (!appIncluded(a.packageName, q, input.categories)) continue
            appTime += overlap(a.start, a.end, r.start, r.end)
            if (a.start in r) launches++
        }
        val filtered = q.packages != null || q.category != null
        val notifications = input.notifications.count {
            it.timestamp in r && (!filtered || appIncluded(it.packageName, q, input.categories)) &&
                it.packageName !in q.excluded
        }
        val trackedDays = trackedDays(input.trackingPeriods, r, q.zone, q.now)
        return Summary(
            screenTime = screenTime,
            screenOnTime = screenOn,
            unlocks = input.unlocks.count { it in r },
            wakes = wakes,
            glances = glances,
            notifications = notifications,
            avgSession = if (unlockedSessions > 0) screenTime / unlockedSessions else 0,
            appTime = appTime,
            appLaunches = launches,
            avgPerDay = if (trackedDays > 0) (if (filtered) appTime else screenTime) / trackedDays else 0,
        )
    }

    fun calculate(input: StatsInput, q: StatsQuery): Stats {
        val r = q.range
        val zone = q.zone
        val filtered = q.packages != null || q.category != null
        val granularity = q.granularity ?: TimeRanges.granularityFor(r, zone)
        val bounds = TimeRanges.boundaries(r, granularity, zone)
        val nb = bounds.size - 1
        val bScreen = LongArray(nb)
        val bApp = LongArray(nb)
        val bUnlocks = IntArray(nb)

        val includedApps = input.appSessions.filter {
            appIncluded(it.packageName, q, input.categories) && it.end > r.start && it.start < r.end
        }
        val unlockedSessions = input.screenSessions
            .filter { it.unlockAt != null && it.end > r.start && it.unlockAt < r.end }
            .sortedBy { it.unlockAt }

        for (s in unlockedSessions) distribute(s.unlockAt!!, s.end, bounds, bScreen)
        for (a in includedApps) distribute(a.start, a.end, bounds, bApp)
        for (u in input.unlocks) bucketOf(u, bounds).takeIf { it >= 0 }?.let { bUnlocks[it]++ }

        val buckets = (0 until nb).map { Bucket(bounds[it], bounds[it + 1], bScreen[it], bUnlocks[it], bApp[it]) }

        // Heatmap & hour-of-day profile (screen time, or app time when a filter is active).
        val heat = Array(7) { LongArray(24) }
        val usageSpans: List<Pair<Long, Long>> =
            if (filtered) includedApps.map { it.start to it.end }
            else unlockedSessions.map { it.unlockAt!! to it.end }
        for ((s, e) in usageSpans) addToHeatmap(maxOf(s, r.start), minOf(e, r.end), zone, heat)
        val hourly = LongArray(24) { h -> heat.sumOf { it[h] } }
        val night = NIGHT_HOURS.sumOf { hourly[it] }

        // Per-app ranking.
        val notifByPkg = input.notifications.filter { it.timestamp in r }.groupingBy { it.packageName }.eachCount()
        val apps = includedApps.groupBy { it.packageName }.map { (pkg, list) ->
            val total = list.sumOf { overlap(it.start, it.end, r.start, r.end) }
            val launches = list.count { it.start in r }
            AppUsage(
                packageName = pkg,
                total = total,
                launches = launches,
                avgSession = if (launches > 0) total / launches else total,
                longestSession = list.maxOf { overlap(it.start, it.end, r.start, r.end) },
                notifications = notifByPkg[pkg] ?: 0,
                lastUsed = list.maxOf { it.end },
                category = input.categories[pkg] ?: AppCategories.UNDEFINED,
            )
        }.filter { it.total > 0 }.sortedByDescending { it.total }

        val categories = apps.groupBy { it.category }
            .map { (c, l) -> CategoryUsage(c, l.sumOf { it.total }, l.size) }
            .sortedByDescending { it.total }

        // Session-level analysis (device sessions, or app sessions when filtered).
        val sessionLengths: List<Long> =
            if (filtered) includedApps.filter { it.start in r }.map { it.end - it.start }
            else unlockedSessions.filter { it.unlockAt!! in r }.map { it.end - it.unlockAt!! }
        val sortedLengths = sessionLengths.sorted()
        val distribution = DIST_BINS.mapIndexed { i, (label, max) ->
            val min = if (i == 0) 0 else DIST_BINS[i - 1].second
            val inBin = sessionLengths.filter { it >= min && it < max }
            DistributionBin(label, max, inBin.size, inBin.sum())
        }

        // Gaps between uses: time between the end of one unlocked session and the next unlock.
        var longestBreak: TimeRange? = null
        val gaps = ArrayList<Long>()
        val gapSource = if (filtered) includedApps.sortedBy { it.start }.map { it.start to it.end }
        else unlockedSessions.map { it.unlockAt!! to it.end }
        for (i in 1 until gapSource.size) {
            val gs = gapSource[i - 1].second
            val ge = gapSource[i].first
            if (ge > gs && !spansUntracked(gs, ge, input.trackingPeriods)) {
                gaps += ge - gs
                if (longestBreak == null || ge - gs > longestBreak.duration) longestBreak = TimeRange(gs, ge)
            }
        }

        val days = dayHabits(input, unlockedSessions, includedApps, q, filtered)
        val trackedDayList = days.filter { isDayTracked(it.date, input.trackingPeriods, zone, q.now) }
        val firstUses = trackedDayList.mapNotNull { it.firstUseMinute }
        val lastUses = trackedDayList.mapNotNull { it.lastUseMinute }
        val daysOverGoal = if (q.dailyGoalMs > 0) trackedDayList.count { it.screenTime > q.dailyGoalMs } else 0

        return Stats(
            range = r,
            granularity = granularity,
            summary = summarize(input, q),
            buckets = buckets,
            heatmap = heat,
            hourly = hourly,
            apps = apps,
            categories = categories,
            days = days,
            sessionDistribution = distribution,
            medianSession = median(sortedLengths),
            longestSession = sortedLengths.lastOrNull() ?: 0,
            medianGapBetweenUnlocks = median(gaps.sorted()),
            longestBreak = longestBreak,
            nightTime = night,
            avgFirstUseMinute = firstUses.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            avgLastUseMinute = lastUses.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            daysOverGoal = daysOverGoal,
            coverage = coverage(input.trackingPeriods, input.gaps, r, q.now),
            filtered = filtered,
        )
    }

    private fun dayHabits(
        input: StatsInput,
        unlocked: List<ScreenSession>,
        apps: List<AppSession>,
        q: StatsQuery,
        filtered: Boolean,
    ): List<DayHabit> {
        val zone = q.zone
        val firstDay = TimeRanges.localDate(q.range.start, zone)
        val lastDay = TimeRanges.localDate(minOf(q.range.end, maxOf(q.now, q.range.start + 1)) - 1, zone)
        val out = ArrayList<DayHabit>()
        var d = firstDay
        while (!d.isAfter(lastDay)) {
            val dr = TimeRanges.dayRange(d, zone)
            val time = if (filtered) apps.sumOf { overlap(it.start, it.end, dr.start, dr.end) }
            else unlocked.sumOf { overlap(it.unlockAt!!, it.end, dr.start, dr.end) }
            val starting = unlocked.filter { it.unlockAt!! in dr }
            out += DayHabit(
                date = d,
                screenTime = time,
                unlocks = input.unlocks.count { it in dr },
                firstUseMinute = starting.minOfOrNull { it.unlockAt!! }?.let { minuteOfDay(it, dr.start) },
                lastUseMinute = starting.maxOfOrNull { it.end }?.let { minuteOfDay(it, dr.start) },
            )
            d = d.plusDays(1)
        }
        return out
    }

    private fun minuteOfDay(ts: Long, dayStart: Long) = ((ts - dayStart) / 60_000L).toInt()

    private fun addToHeatmap(s: Long, e: Long, zone: ZoneId, heat: Array<LongArray>) {
        if (e <= s) return
        var t = Instant.ofEpochMilli(s).atZone(zone)
        var cur = s
        while (cur < e) {
            val hourEnd = t.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val segEnd = minOf(hourEnd, e)
            val dow = t.dayOfWeek.value - DayOfWeek.MONDAY.value
            heat[dow][t.hour] += segEnd - cur
            cur = segEnd
            t = Instant.ofEpochMilli(cur).atZone(zone)
        }
    }

    private fun median(sorted: List<Long>): Long = when {
        sorted.isEmpty() -> 0
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun spansUntracked(s: Long, e: Long, periods: List<TrackingPeriod>): Boolean =
        periods.isNotEmpty() && periods.none { p -> p.start <= s && (p.end ?: Long.MAX_VALUE) >= e }

    private fun isDayTracked(date: LocalDate, periods: List<TrackingPeriod>, zone: ZoneId, now: Long): Boolean {
        val dr = TimeRanges.dayRange(date, zone)
        return periods.any { overlap(it.start, it.end ?: now, dr.start, dr.end) > 0 }
    }

    fun trackedDays(periods: List<TrackingPeriod>, r: TimeRange, zone: ZoneId, now: Long): Int {
        var d = TimeRanges.localDate(r.start, zone)
        val last = TimeRanges.localDate(minOf(r.end, maxOf(now, r.start + 1)) - 1, zone)
        var n = 0
        while (!d.isAfter(last)) {
            if (isDayTracked(d, periods, zone, now)) n++
            d = d.plusDays(1)
        }
        return n
    }

    fun coverage(periods: List<TrackingPeriod>, gaps: List<DataGap>, r: TimeRange, now: Long): Coverage {
        val elapsedEnd = minOf(r.end, now)
        val elapsed = (elapsedEnd - r.start).coerceAtLeast(0)
        // Merge tracked intervals within the range, minus data gaps.
        val tracked = periods.map { maxOf(it.start, r.start) to minOf(it.end ?: now, elapsedEnd) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>()
        for (iv in tracked) {
            if (merged.isNotEmpty() && iv.first <= merged.last().second) {
                merged[merged.lastIndex] = merged.last().first to maxOf(merged.last().second, iv.second)
            } else merged += iv
        }
        val gapsIn = gaps.filter { it.end > r.start && it.start < elapsedEnd }
        val gapMs = gapsIn.sumOf { g -> merged.sumOf { overlap(g.start, g.end, it.first, it.second) } }
        val trackedMs = (merged.sumOf { it.second - it.first } - gapMs).coerceAtLeast(0)
        val untracked = ArrayList<TimeRange>()
        var cursor = r.start
        for ((s, e) in merged) {
            if (s > cursor) untracked += TimeRange(cursor, s)
            cursor = maxOf(cursor, e)
        }
        if (cursor < elapsedEnd) untracked += TimeRange(cursor, elapsedEnd)
        return Coverage(
            ratio = if (elapsed > 0) trackedMs.toDouble() / elapsed else 0.0,
            trackedMs = trackedMs,
            elapsedMs = elapsed,
            untracked = untracked,
            dataGaps = gapsIn,
        )
    }
}

/** Mirrors `android.content.pm.ApplicationInfo.CATEGORY_*` values. */
object AppCategories {
    const val UNDEFINED = -1
    const val GAME = 0
    const val AUDIO = 1
    const val VIDEO = 2
    const val IMAGE = 3
    const val SOCIAL = 4
    const val NEWS = 5
    const val MAPS = 6
    const val PRODUCTIVITY = 7
    const val ACCESSIBILITY = 8

    val all = listOf(SOCIAL, VIDEO, GAME, AUDIO, IMAGE, NEWS, MAPS, PRODUCTIVITY, ACCESSIBILITY, UNDEFINED)

    fun label(category: Int): String = when (category) {
        GAME -> "Games"
        AUDIO -> "Music & audio"
        VIDEO -> "Video"
        IMAGE -> "Photos"
        SOCIAL -> "Social"
        NEWS -> "News"
        MAPS -> "Maps & travel"
        PRODUCTIVITY -> "Productivity"
        ACCESSIBILITY -> "Accessibility"
        else -> "Other"
    }
}

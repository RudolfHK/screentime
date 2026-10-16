package io.github.rudolfhk.screentime.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Half-open interval [start, end) in epoch millis. */
data class TimeRange(val start: Long, val end: Long) {
    val duration: Long get() = (end - start).coerceAtLeast(0)
    operator fun contains(ts: Long): Boolean = ts in start until end
}

enum class RangeKind { DAY, WEEK, MONTH, ALL, CUSTOM }

enum class Granularity { HOUR, DAY, WEEK, MONTH }

/** A user-facing period selection: kind + anchor date (+ explicit dates for CUSTOM). */
data class PeriodSelection(
    val kind: RangeKind,
    val anchor: LocalDate,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
)

object TimeRanges {

    fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun localDate(ts: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    fun dayRange(date: LocalDate, zone: ZoneId) =
        TimeRange(startOfDay(date, zone), startOfDay(date.plusDays(1), zone))

    /**
     * Resolves the selection to a concrete range. [allTimeStart] is used for [RangeKind.ALL];
     * [now] caps ALL so the range never extends into the future.
     */
    fun resolve(sel: PeriodSelection, zone: ZoneId, allTimeStart: Long, now: Long): TimeRange =
        when (sel.kind) {
            RangeKind.DAY -> dayRange(sel.anchor, zone)
            RangeKind.WEEK -> {
                val monday = sel.anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                TimeRange(startOfDay(monday, zone), startOfDay(monday.plusWeeks(1), zone))
            }
            RangeKind.MONTH -> {
                val first = sel.anchor.withDayOfMonth(1)
                TimeRange(startOfDay(first, zone), startOfDay(first.plusMonths(1), zone))
            }
            RangeKind.ALL -> {
                val startDay = localDate(minOf(allTimeStart, now), zone)
                TimeRange(startOfDay(startDay, zone), startOfDay(localDate(now, zone).plusDays(1), zone))
            }
            RangeKind.CUSTOM -> {
                val s = sel.customStart ?: sel.anchor
                val e = sel.customEnd ?: s
                val (a, b) = if (e.isBefore(s)) e to s else s to e
                TimeRange(startOfDay(a, zone), startOfDay(b.plusDays(1), zone))
            }
        }

    /** The equally long period immediately before [sel], or null when it has no meaning (ALL). */
    fun previous(sel: PeriodSelection): PeriodSelection? = shift(sel, -1)

    fun shift(sel: PeriodSelection, delta: Long): PeriodSelection? = when (sel.kind) {
        RangeKind.DAY -> sel.copy(anchor = sel.anchor.plusDays(delta))
        RangeKind.WEEK -> sel.copy(anchor = sel.anchor.plusWeeks(delta))
        RangeKind.MONTH -> sel.copy(anchor = sel.anchor.plusMonths(delta))
        RangeKind.ALL -> null
        RangeKind.CUSTOM -> {
            val s = sel.customStart ?: sel.anchor
            val e = sel.customEnd ?: s
            val len = ChronoUnit.DAYS.between(s, e) + 1
            sel.copy(customStart = s.plusDays(len * delta), customEnd = e.plusDays(len * delta))
        }
    }

    fun granularityFor(range: TimeRange, zone: ZoneId): Granularity {
        val days = ChronoUnit.DAYS.between(localDate(range.start, zone), localDate(range.end - 1, zone)) + 1
        return when {
            days <= 1 -> Granularity.HOUR
            days <= 62 -> Granularity.DAY
            days <= 366 -> Granularity.WEEK
            else -> Granularity.MONTH
        }
    }

    /** Bucket boundaries covering [range]; size = buckets + 1. DST-safe (uses zoned arithmetic). */
    fun boundaries(range: TimeRange, granularity: Granularity, zone: ZoneId): LongArray {
        val out = ArrayList<Long>()
        var t: ZonedDateTime = Instant.ofEpochMilli(range.start).atZone(zone)
        t = when (granularity) {
            Granularity.HOUR -> t.truncatedTo(ChronoUnit.HOURS)
            Granularity.DAY -> t.toLocalDate().atStartOfDay(zone)
            Granularity.WEEK -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)
            Granularity.MONTH -> t.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
        }
        while (true) {
            val ms = t.toInstant().toEpochMilli()
            out += maxOf(ms, range.start)
            if (ms >= range.end) break
            t = when (granularity) {
                Granularity.HOUR -> t.plusHours(1)
                Granularity.DAY -> t.plusDays(1)
                Granularity.WEEK -> t.plusWeeks(1)
                Granularity.MONTH -> t.plusMonths(1)
            }
        }
        out[out.lastIndex] = range.end
        return out.distinct().toLongArray()
    }

    fun bucketLabel(start: Long, granularity: Granularity, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val z = Instant.ofEpochMilli(start).atZone(zone)
        val pattern = when (granularity) {
            Granularity.HOUR -> "HH"
            Granularity.DAY -> "EEE d"
            Granularity.WEEK -> "d MMM"
            Granularity.MONTH -> "MMM yy"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(z)
    }

    fun label(sel: PeriodSelection, range: TimeRange, zone: ZoneId, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val df = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        val short = DateTimeFormatter.ofPattern("d MMM", locale)
        val first = localDate(range.start, zone)
        val last = localDate(range.end - 1, zone)
        return when (sel.kind) {
            RangeKind.DAY -> when (sel.anchor) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale).format(sel.anchor)
            }
            RangeKind.MONTH -> DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(sel.anchor)
            RangeKind.ALL -> "All time · since ${df.format(first)}"
            RangeKind.WEEK, RangeKind.CUSTOM ->
                if (first.year == last.year) "${short.format(first)} – ${df.format(last)}"
                else "${df.format(first)} – ${df.format(last)}"
        }
    }
}

/** Length of the overlap between [s, e) and [from, to). */
fun overlap(s: Long, e: Long, from: Long, to: Long): Long =
    (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0)

/**
 * Adds the parts of [s, e) that fall into each bucket defined by [bounds] to [acc].
 * [bounds] must be sorted; acc.size == bounds.size - 1.
 */
fun distribute(s: Long, e: Long, bounds: LongArray, acc: LongArray) {
    if (bounds.size < 2 || e <= bounds.first() || s >= bounds.last()) return
    var i = bounds.binarySearch(s).let { if (it >= 0) it else -it - 2 }.coerceIn(0, bounds.size - 2)
    while (i < bounds.size - 1 && bounds[i] < e) {
        acc[i] += overlap(s, e, bounds[i], bounds[i + 1])
        i++
    }
}

/** Index of the bucket containing [ts], or -1. */
fun bucketOf(ts: Long, bounds: LongArray): Int {
    if (bounds.size < 2 || ts < bounds.first() || ts >= bounds.last()) return -1
    val i = bounds.binarySearch(ts)
    return if (i >= 0) i.coerceAtMost(bounds.size - 2) else -i - 2
}

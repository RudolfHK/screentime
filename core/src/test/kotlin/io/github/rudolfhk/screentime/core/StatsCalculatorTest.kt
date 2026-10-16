package io.github.rudolfhk.screentime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class StatsCalculatorTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 3, 2) // a Monday
    private val d0 = TimeRanges.startOfDay(day, zone)
    private val h = 3_600_000L
    private val m = 60_000L

    private fun input(
        screens: List<ScreenSession>,
        apps: List<AppSession> = emptyList(),
        unlocks: List<Long> = screens.mapNotNull { it.unlockAt },
        notifications: List<NotificationEvent> = emptyList(),
    ) = StatsInput(
        screenSessions = screens,
        appSessions = apps,
        unlocks = unlocks,
        notifications = notifications,
        trackingPeriods = listOf(TrackingPeriod(d0 - 30 * 24 * h, null)),
        gaps = emptyList(),
        categories = mapOf("social" to AppCategories.SOCIAL, "work" to AppCategories.PRODUCTIVITY),
    )

    private fun query(range: TimeRange = TimeRanges.dayRange(day, zone), now: Long = d0 + 30 * 24 * h, packages: Set<String>? = null) =
        StatsQuery(range = range, zone = zone, now = now, packages = packages, dailyGoalMs = 30 * m)

    @Test
    fun `day totals and hourly buckets`() {
        val screens = listOf(
            ScreenSession(d0 + 8 * h, d0 + 8 * h + 20 * m, d0 + 8 * h),          // 20m
            ScreenSession(d0 + 9 * h + 50 * m, d0 + 10 * h + 10 * m, d0 + 9 * h + 50 * m), // 20m across hours
            ScreenSession(d0 + 12 * h, d0 + 12 * h + m, null),                    // glance
        )
        val apps = listOf(
            AppSession("social", d0 + 8 * h, d0 + 8 * h + 15 * m),
            AppSession("work", d0 + 9 * h + 50 * m, d0 + 10 * h + 10 * m),
        )
        val s = StatsCalculator.calculate(input(screens, apps), query())
        assertEquals(40 * m, s.summary.screenTime)
        assertEquals(41 * m, s.summary.screenOnTime)
        assertEquals(2, s.summary.unlocks)
        assertEquals(3, s.summary.wakes)
        assertEquals(1, s.summary.glances)
        assertEquals(20 * m, s.summary.avgSession)
        assertEquals(Granularity.HOUR, s.granularity)
        assertEquals(24, s.buckets.size)
        assertEquals(20 * m, s.buckets[8].screenTime)
        assertEquals(10 * m, s.buckets[9].screenTime)
        assertEquals(10 * m, s.buckets[10].screenTime)
        assertEquals(listOf("work", "social"), s.apps.map { it.packageName })
        assertEquals(1, s.daysOverGoal)
        assertEquals(8 * 60, s.avgFirstUseMinute)
        assertEquals(10 * 60 + 10, s.avgLastUseMinute)
        // Gap between first session end (08:20) and second unlock (09:50).
        assertEquals(90 * m, s.medianGapBetweenUnlocks)
        assertEquals(TimeRange(d0 + 8 * h + 20 * m, d0 + 9 * h + 50 * m), s.longestBreak)
        assertEquals(20 * m, s.heatmap[0][8])
    }

    @Test
    fun `app filter switches to app metrics`() {
        val apps = listOf(
            AppSession("social", d0 + h, d0 + h + 5 * m),
            AppSession("social", d0 + 2 * h, d0 + 2 * h + 10 * m),
            AppSession("work", d0 + 3 * h, d0 + 3 * h + 30 * m),
        )
        val s = StatsCalculator.calculate(input(emptyList(), apps), query(packages = setOf("social")))
        assertTrue(s.filtered)
        assertEquals(15 * m, s.summary.appTime)
        assertEquals(2, s.summary.appLaunches)
        assertEquals(1, s.apps.size)
        assertEquals(7 * m + 30_000, s.apps[0].avgSession)
        assertEquals(10 * m, s.longestSession)
    }

    @Test
    fun `excluded apps are dropped from rankings`() {
        val apps = listOf(AppSession("launcher", d0, d0 + h), AppSession("social", d0 + h, d0 + 2 * h))
        val q = query().copy(excluded = setOf("launcher"))
        val s = StatsCalculator.calculate(input(emptyList(), apps), q)
        assertEquals(listOf("social"), s.apps.map { it.packageName })
    }

    @Test
    fun `category filter and grouping`() {
        val apps = listOf(AppSession("social", d0, d0 + h), AppSession("work", d0 + h, d0 + 3 * h))
        val all = StatsCalculator.calculate(input(emptyList(), apps), query())
        assertEquals(AppCategories.PRODUCTIVITY, all.categories.first().category)
        val social = StatsCalculator.calculate(input(emptyList(), apps), query().copy(category = AppCategories.SOCIAL))
        assertEquals(h, social.summary.appTime)
    }

    @Test
    fun `sessions spanning midnight split into both days`() {
        val screens = listOf(ScreenSession(d0 - 30 * m, d0 + 30 * m, d0 - 30 * m))
        val week = TimeRanges.resolve(PeriodSelection(RangeKind.WEEK, day), zone, 0, d0 + 10 * 24 * h)
        val s = StatsCalculator.calculate(input(screens), query(range = week))
        assertEquals(Granularity.DAY, s.granularity)
        assertEquals(7, s.buckets.size)
        assertEquals(30 * m, s.buckets[0].screenTime) // only the part after Monday 00:00
        assertEquals(30 * m, s.nightTime)
    }

    @Test
    fun `session length distribution`() {
        val screens = listOf(
            ScreenSession(d0, d0 + 5_000, d0),
            ScreenSession(d0 + h, d0 + h + 2 * m, d0 + h),
            ScreenSession(d0 + 2 * h, d0 + 4 * h, d0 + 2 * h),
        )
        val s = StatsCalculator.calculate(input(screens), query())
        val counts = s.sessionDistribution.associate { it.label to it.count }
        assertEquals(1, counts["<10s"])
        assertEquals(1, counts["1–5m"])
        assertEquals(1, counts[">1h"])
    }

    @Test
    fun `coverage reports untracked spans and gaps`() {
        val periods = listOf(TrackingPeriod(d0 + 6 * h, d0 + 18 * h))
        val gaps = listOf(DataGap(d0 + 10 * h, d0 + 12 * h))
        val c = StatsCalculator.coverage(periods, gaps, TimeRanges.dayRange(day, zone), now = d0 + 48 * h)
        assertEquals(10 * h, c.trackedMs)
        assertEquals(24 * h, c.elapsedMs)
        assertEquals(listOf(TimeRange(d0, d0 + 6 * h), TimeRange(d0 + 18 * h, d0 + 24 * h)), c.untracked)
        assertEquals(1, c.dataGaps.size)
    }

    @Test
    fun `notifications are counted per app`() {
        val apps = listOf(AppSession("social", d0, d0 + m))
        val n = listOf(NotificationEvent("social", d0 + 5), NotificationEvent("social", d0 + 6), NotificationEvent("work", d0 + 7))
        val s = StatsCalculator.calculate(input(emptyList(), apps, notifications = n), query())
        assertEquals(3, s.summary.notifications)
        assertEquals(2, s.apps.single().notifications)
    }

    @Test
    fun `range navigation`() {
        val sel = PeriodSelection(RangeKind.MONTH, LocalDate.of(2026, 1, 15))
        val prev = TimeRanges.previous(sel)
        assertNotNull(prev)
        assertEquals(LocalDate.of(2025, 12, 15), prev!!.anchor)
        val custom = PeriodSelection(RangeKind.CUSTOM, day, day, day.plusDays(2))
        val shifted = TimeRanges.shift(custom, 1)!!
        assertEquals(day.plusDays(3), shifted.customStart)
        assertEquals(day.plusDays(5), shifted.customEnd)
        val r = TimeRanges.resolve(custom, zone, 0, 0)
        assertEquals(3 * 24 * h, r.duration)
    }

    @Test
    fun `boundaries respect DST`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val dstDay = LocalDate.of(2026, 3, 29) // clocks go forward, 23h day
        val r = TimeRanges.dayRange(dstDay, berlin)
        val b = TimeRanges.boundaries(r, Granularity.HOUR, berlin)
        assertEquals(24, b.size) // 23 buckets
        assertEquals(23 * h, r.duration)
    }

    @Test
    fun `format helpers`() {
        assertEquals("3h 12m", Format.duration(3 * h + 12 * m))
        assertEquals("45m", Format.duration(45 * m))
        assertEquals("38s", Format.duration(38_000))
        assertEquals("07:05", Format.minuteOfDay(7 * 60 + 5))
        assertEquals("+50%", Format.delta(150, 100))
        assertEquals("−25%", Format.delta(75, 100))
    }
}

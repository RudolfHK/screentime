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
    fun `insights over a week`() {
        // Mon 10m, Tue 40m, Wed 20m, Sat 60m; goal is 30m.
        val day = 24 * h
        val screens = listOf(
            ScreenSession(d0 + 9 * h, d0 + 9 * h + 10 * m, d0 + 9 * h),
            ScreenSession(d0 + day + 9 * h, d0 + day + 9 * h + 40 * m, d0 + day + 9 * h),
            ScreenSession(d0 + 2 * day + 9 * h, d0 + 2 * day + 9 * h + 20 * m, d0 + 2 * day + 9 * h),
            ScreenSession(d0 + 5 * day + 20 * h, d0 + 5 * day + 21 * h, d0 + 5 * day + 20 * h),
        )
        val week = TimeRanges.resolve(PeriodSelection(RangeKind.WEEK, day0()), zone, 0, d0 + 30 * day)
        val s = StatsCalculator.calculate(input(screens), query(range = week))
        val i = s.insights
        assertEquals(10 * m, i.weekdayAverages[0])
        assertEquals(60 * m, i.weekdayAverages[5])
        assertEquals(70 * m / 5, i.weekdayAvg)
        assertEquals(30 * m, i.weekendAvg)
        assertEquals(day0().plusDays(5), i.busiestDay!!.date)
        assertEquals(0L, i.quietestDay!!.screenTime)
        assertEquals(7, i.trackedDays)
        assertEquals(4, i.sessions)
        assertEquals(130 * m / 4, i.screenTimePerUnlock)
        assertEquals(9, i.peakHour) // 09:00 holds 70m vs. 60m at 20:00
        // Sat (60m) breaks the run; Sun (0m) starts a new one.
        assertEquals(1, i.currentGoalStreak)
        assertEquals(3, i.bestGoalStreak)
    }

    private fun day0() = day

    @Test
    fun `comparison uses the same elapsed part of the previous period`() {
        val day = 24 * h
        val week = TimeRange(d0, d0 + 7 * day)
        val prev = TimeRange(d0 - 7 * day, d0)
        // Wednesday noon: compare with last Monday 00:00 – Wednesday 12:00.
        val now = d0 + 2 * day + 12 * h
        assertEquals(TimeRange(d0 - 7 * day, d0 - 7 * day + 2 * day + 12 * h), TimeRanges.comparablePrevious(week, prev, now))
        // Finished periods are compared in full.
        val older = TimeRange(d0 - 14 * day, d0 - 7 * day)
        assertEquals(older, TimeRanges.comparablePrevious(prev, older, now))
    }

    @Test
    fun `notification stats include apps that were never opened`() {
        val apps = listOf(AppSession("social", d0 + 10 * h + 2 * m, d0 + 10 * h + 5 * m))
        val n = listOf(
            NotificationEvent("social", d0 + 10 * h),          // opened 2 min later
            NotificationEvent("social", d0 + 15 * h),          // not opened
            NotificationEvent("bank", d0 + 15 * h + m),        // app never used
            NotificationEvent("launcher", d0 + 16 * h),        // excluded
        )
        val q = query().copy(excluded = setOf("launcher"))
        val s = StatsCalculator.calculate(input(emptyList(), apps, notifications = n), q)
        val ns = s.notifications
        assertEquals(3, ns.total)
        assertEquals(3, s.summary.notifications)
        assertEquals(listOf("social" to 2, "bank" to 1), ns.byApp)
        assertEquals(1, ns.hourly[10])
        assertEquals(2, ns.hourly[15])
        assertEquals(1.0 / 3, ns.openedWithin10Min, 1e-9)
        assertEquals(3, s.buckets.sumOf { it.notifications })
        assertEquals(3.0, ns.perLaunch, 1e-9)
    }

    @Test
    fun `per-app breakdown over time`() {
        val day = 24 * h
        val apps = listOf(
            AppSession("social", d0 + 9 * h, d0 + 9 * h + 20 * m),            // Mon 09:00
            AppSession("social", d0 + day + 21 * h, d0 + day + 21 * h + 40 * m), // Tue 21:00
            AppSession("work", d0 + day + 10 * h, d0 + day + 10 * h + 5 * m),
        )
        val week = TimeRanges.resolve(PeriodSelection(RangeKind.WEEK, day0()), zone, 0, d0 + 30 * day)
        val s = StatsCalculator.calculate(input(emptyList(), apps), query(range = week))
        val social = s.apps.first { it.packageName == "social" }
        assertEquals(listOf(20 * m, 40 * m, 0L, 0L, 0L, 0L, 0L), social.buckets.toList())
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 0), social.launchBuckets.toList())
        assertEquals(40 * m, social.hourly[21])
        assertEquals(21, social.peakHour)
        assertEquals(2, social.activeDays)
        assertEquals(30 * m, social.medianSession)
        assertEquals(d0 + 9 * h, social.firstUsed)

        val tuesday = s.days[1]
        assertEquals(listOf("social" to 40 * m, "work" to 5 * m), tuesday.topApps)
        assertEquals(45 * m, tuesday.appTime)
        assertEquals(2, tuesday.launches)

        val totals = StatsCalculator.appTotals(input(emptyList(), apps), query(range = week))
        assertEquals(mapOf("social" to 60 * m, "work" to 5 * m), totals)
    }

    @Test
    fun `filtered day habits use app sessions for first and last use`() {
        val apps = listOf(AppSession("social", d0 + 8 * h, d0 + 8 * h + 10 * m), AppSession("social", d0 + 22 * h, d0 + 22 * h + 5 * m))
        val s = StatsCalculator.calculate(input(emptyList(), apps), query(packages = setOf("social")))
        assertEquals(8 * 60, s.days.single().firstUseMinute)
        assertEquals(22 * 60 + 5, s.days.single().lastUseMinute)
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

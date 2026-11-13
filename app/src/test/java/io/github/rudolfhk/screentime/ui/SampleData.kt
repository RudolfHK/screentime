package io.github.rudolfhk.screentime.ui

import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.core.AppSession
import io.github.rudolfhk.screentime.core.NotificationEvent
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.core.ScreenSession
import io.github.rudolfhk.screentime.core.StatsCalculator
import io.github.rudolfhk.screentime.core.StatsInput
import io.github.rudolfhk.screentime.core.StatsQuery
import io.github.rudolfhk.screentime.core.TimeRanges
import io.github.rudolfhk.screentime.core.TrackingPeriod
import io.github.rudolfhk.screentime.data.AppInfoEntity
import io.github.rudolfhk.screentime.data.Settings
import io.github.rudolfhk.screentime.data.StatsBundle
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Deterministic, realistic-looking usage data for screenshot tests. */
object SampleData {
    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private const val M = 60_000L

    private val apps = listOf(
        Triple("com.instagram.android", "Instagram", AppCategories.SOCIAL),
        Triple("com.google.android.youtube", "YouTube", AppCategories.VIDEO),
        Triple("com.whatsapp", "WhatsApp", AppCategories.SOCIAL),
        Triple("com.spotify.music", "Spotify", AppCategories.AUDIO),
        Triple("com.android.chrome", "Chrome", AppCategories.PRODUCTIVITY),
        Triple("com.google.android.gm", "Gmail", AppCategories.PRODUCTIVITY),
        Triple("com.google.android.apps.maps", "Maps", AppCategories.MAPS),
        Triple("com.reddit.frontpage", "Reddit", AppCategories.NEWS),
        Triple("com.supercell.clashroyale", "Clash Royale", AppCategories.GAME),
        Triple("org.telegram.messenger", "Telegram", AppCategories.SOCIAL),
    )
    private val weights = listOf(22, 18, 14, 6, 10, 5, 3, 9, 7, 6)

    val appInfo: Map<String, AppInfoEntity> = apps.associate { (p, l, c) -> p to AppInfoEntity(p, l, c, 0) }

    val settings = Settings(
        trackingEnabled = true,
        lastSyncAt = System.currentTimeMillis() - 4 * M,
        dailyGoalMinutes = 180,
        countNotifications = true,
        hasEverTracked = true,
    )

    /** 35 days ending now. */
    val input: StatsInput by lazy {
        val rnd = Random(42)
        val screens = ArrayList<ScreenSession>()
        val appSessions = ArrayList<AppSession>()
        val unlocks = ArrayList<Long>()
        val notifications = ArrayList<NotificationEvent>()
        val now = System.currentTimeMillis()
        for (d in 34 downTo 0) {
            val date = today.minusDays(d.toLong())
            val dayStart = TimeRanges.startOfDay(date, zone)
            val weekend = date.dayOfWeek.value >= 6
            var t = dayStart + (if (weekend) 8 * 60 + 40 else 6 * 60 + 50) * M + rnd.nextLong(40) * M
            val end = dayStart + (23 * 60 + rnd.nextLong(70)) * M
            while (t < end && t < now) {
                val glance = rnd.nextInt(5) == 0
                val len = if (glance) rnd.nextLong(5_000, 40_000) else when (rnd.nextInt(10)) {
                    in 0..3 -> rnd.nextLong(10_000, 60_000)
                    in 4..7 -> rnd.nextLong(1, 12) * M
                    else -> rnd.nextLong(15, if (weekend) 70 else 45) * M
                }
                val sEnd = minOf(t + len, now)
                if (glance) {
                    screens += ScreenSession(t, sEnd, null)
                } else {
                    val unlockAt = t + 1_500
                    screens += ScreenSession(t, sEnd, unlockAt)
                    unlocks += unlockAt
                    var a = unlockAt + 800
                    while (a < sEnd - 2_000) {
                        val idx = pick(rnd)
                        val aLen = minOf(sEnd - a, rnd.nextLong(8_000, maxOf(9_000, (sEnd - a))))
                        appSessions += AppSession(apps[idx].first, a, a + aLen)
                        a += aLen + 500
                    }
                }
                if (rnd.nextInt(3) == 0) notifications += NotificationEvent(apps[pick(rnd)].first, t - rnd.nextLong(1, 10) * M)
                val hour = ((t - dayStart) / (60 * M)).toInt()
                val gap = when (hour) {
                    in 7..9, in 12..13, in 19..22 -> rnd.nextLong(4, 25)
                    else -> rnd.nextLong(15, 70)
                } * M
                t = sEnd + gap
            }
        }
        StatsInput(
            screenSessions = screens,
            appSessions = appSessions,
            unlocks = unlocks,
            notifications = notifications,
            trackingPeriods = listOf(TrackingPeriod(TimeRanges.startOfDay(today.minusDays(34), zone), null)),
            gaps = emptyList(),
            categories = apps.associate { it.first to it.third },
        )
    }

    private fun pick(rnd: Random): Int {
        var r = rnd.nextInt(weights.sum())
        weights.forEachIndexed { i, w -> if (r < w) return i else r -= w }
        return 0
    }

    fun bundle(selection: PeriodSelection, packages: Set<String>? = null): StatsBundle {
        val now = System.currentTimeMillis()
        val first = input.trackingPeriods.first().start
        val range = TimeRanges.resolve(selection, zone, first, now)
        val q = StatsQuery(range, zone, now, packages = packages, dailyGoalMs = settings.dailyGoalMinutes * M)
        val prev = TimeRanges.previous(selection)?.let { TimeRanges.resolve(it, zone, first, now) }
        return StatsBundle(
            selection = selection,
            stats = StatsCalculator.calculate(input, q),
            previous = prev?.let { StatsCalculator.summarize(input, q.copy(range = it)) },
            apps = appInfo,
            settings = settings,
            firstTrackedAt = first,
            timeline = if (selection.kind == RangeKind.DAY) {
                input.screenSessions.filter { it.end > range.start && it.start < range.end }.map { Triple(it.start, it.end, it.unlockAt != null) }
            } else emptyList(),
        )
    }
}

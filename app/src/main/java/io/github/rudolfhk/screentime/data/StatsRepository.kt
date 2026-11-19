package io.github.rudolfhk.screentime.data

import io.github.rudolfhk.screentime.core.AppSession
import io.github.rudolfhk.screentime.core.DataGap
import io.github.rudolfhk.screentime.core.NotificationEvent
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.core.ScreenSession
import io.github.rudolfhk.screentime.core.Stats
import io.github.rudolfhk.screentime.core.StatsCalculator
import io.github.rudolfhk.screentime.core.StatsInput
import io.github.rudolfhk.screentime.core.StatsQuery
import io.github.rudolfhk.screentime.core.Summary
import io.github.rudolfhk.screentime.core.TimeRange
import io.github.rudolfhk.screentime.core.TimeRanges
import io.github.rudolfhk.screentime.core.TrackingPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

data class StatsBundle(
    val selection: PeriodSelection,
    val stats: Stats,
    val previous: Summary?,
    val apps: Map<String, AppInfoEntity>,
    val settings: Settings,
    val firstTrackedAt: Long?,
    /** The window the comparison numbers come from. */
    val previousRange: TimeRange? = null,
    /** True when the current period is still running and only its elapsed part is compared. */
    val partialComparison: Boolean = false,
    /** Tracking log: start/stop/backfill/gaps (all time) and syncs of the last 30 days, newest first. */
    val log: List<TrackingLogEntity> = emptyList(),
    /** Time per app in the previous period, for per-app change badges. */
    val previousApps: Map<String, Long> = emptyMap(),
    /** Screen sessions (start, end, unlocked) – only filled for single-day ranges. */
    val timeline: List<Triple<Long, Long, Boolean>> = emptyList(),
)

data class TodaySnapshot(val screenTime: Long, val unlocks: Int, val goalMs: Long, val tracking: Boolean)

/** Reactive stats: recomputed whenever the underlying tables change (i.e. after each sync). */
class StatsRepository(private val db: AppDatabase, private val settingsStore: SettingsStore) {

    private val dao = db.usageDao()

    private data class Config(
        val goalMinutes: Int,
        val excluded: Set<String>,
        val firstTrackedAt: Long?,
        val settings: Settings,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stats(selection: PeriodSelection, packages: Set<String>?, category: Int?): Flow<StatsBundle> =
        combine(settingsStore.settings, dao.firstTrackedAt()) { s, first -> Config(s.dailyGoalMinutes, s.excludedPackages, first, s) }
            .distinctUntilChanged { a, b ->
                a.goalMinutes == b.goalMinutes && a.excluded == b.excluded && a.firstTrackedAt == b.firstTrackedAt &&
                    a.settings.trackingEnabled == b.settings.trackingEnabled
            }
            .flatMapLatest { cfg ->
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val range = TimeRanges.resolve(selection, zone, cfg.firstTrackedAt ?: now, now)
                val prevRange = TimeRanges.previous(selection)?.let { TimeRanges.resolve(it, zone, cfg.firstTrackedAt ?: now, now) }
                val from = minOf(range.start, prevRange?.start ?: range.start)
                combine(inputFlow(from, range.end), dao.log(now - LOG_SYNC_WINDOW_MS)) { data, log -> data to log }.map { (data, log) ->
                    val (input, apps) = data
                    val q = StatsQuery(
                        range = range,
                        zone = zone,
                        now = System.currentTimeMillis(),
                        packages = packages,
                        category = category,
                        excluded = cfg.excluded,
                        dailyGoalMs = cfg.goalMinutes * 60_000L,
                    )
                    // Like-for-like: a running period is compared with the same elapsed part of the previous one.
                    val compareRange = prevRange?.let { TimeRanges.comparablePrevious(range, it, q.now) }
                    StatsBundle(
                        selection = selection,
                        stats = StatsCalculator.calculate(input, q),
                        previous = compareRange?.let { StatsCalculator.summarize(input, q.copy(range = it)) },
                        previousApps = compareRange?.let { StatsCalculator.appTotals(input, q.copy(range = it)) } ?: emptyMap(),
                        previousRange = compareRange,
                        partialComparison = compareRange != null && compareRange != prevRange,
                        log = log,
                        apps = apps,
                        settings = cfg.settings,
                        firstTrackedAt = cfg.firstTrackedAt,
                        timeline = if (selection.kind == RangeKind.DAY) {
                            input.screenSessions
                                .filter { it.end > range.start && it.start < range.end }
                                .map { Triple(it.start, it.end, it.unlockAt != null) }
                        } else emptyList(),
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    private fun inputFlow(from: Long, to: Long): Flow<Pair<StatsInput, Map<String, AppInfoEntity>>> {
        val sessions = combine(
            dao.screenSessions(from, to),
            dao.appSessions(from, to),
            dao.unlocks(from, to),
            dao.notifications(from, to),
        ) { screens, apps, unlocks, notifications ->
            Quad(screens, apps, unlocks, notifications)
        }
        return combine(sessions, dao.trackingPeriods(), dao.gaps(), dao.apps()) { q, periods, gaps, apps ->
            val appMap = apps.associateBy { it.packageName }
            StatsInput(
                screenSessions = q.a.map { ScreenSession(it.start, it.end, it.unlockAt) },
                appSessions = q.b.map { AppSession(it.packageName, it.start, it.end) },
                unlocks = q.c,
                notifications = q.d.map { NotificationEvent(it.packageName, it.ts) },
                trackingPeriods = periods.map { TrackingPeriod(it.start, it.end, it.backfilled) },
                gaps = gaps.map { DataGap(it.start, it.end) },
                categories = apps.associate { it.packageName to it.category },
            ) to appMap
        }
    }

    suspend fun todaySnapshot(): TodaySnapshot {
        val zone = ZoneId.systemDefault()
        val s = settingsStore.current()
        val bundle = stats(PeriodSelection(RangeKind.DAY, LocalDate.now(zone)), null, null).first()
        return TodaySnapshot(
            screenTime = bundle.stats.summary.screenTime,
            unlocks = bundle.stats.summary.unlocks,
            goalMs = s.dailyGoalMinutes * 60_000L,
            tracking = s.trackingEnabled,
        )
    }

    /** Whole-history stats for the CSV daily summary. */
    suspend fun allTime(): Stats {
        val now = System.currentTimeMillis()
        val first = dao.firstTrackedAt().first() ?: now
        val zone = ZoneId.systemDefault()
        val range: TimeRange = TimeRanges.resolve(PeriodSelection(RangeKind.ALL, LocalDate.now(zone)), zone, first, now)
        val (input, _) = inputFlow(range.start, range.end).first()
        val s = settingsStore.current()
        return StatsCalculator.calculate(
            input,
            StatsQuery(range, zone, now, excluded = s.excludedPackages, dailyGoalMs = s.dailyGoalMinutes * 60_000L),
        )
    }

    companion object {
        private const val LOG_SYNC_WINDOW_MS = 30 * 86_400_000L
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}

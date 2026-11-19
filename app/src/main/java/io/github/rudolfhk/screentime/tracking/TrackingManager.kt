package io.github.rudolfhk.screentime.tracking

import android.content.Context
import androidx.room.withTransaction
import io.github.rudolfhk.screentime.core.RawEvent
import io.github.rudolfhk.screentime.core.SessionBuilder
import io.github.rudolfhk.screentime.data.AppDatabase
import io.github.rudolfhk.screentime.data.AppSessionEntity
import io.github.rudolfhk.screentime.data.DataGapEntity
import io.github.rudolfhk.screentime.data.LogType
import io.github.rudolfhk.screentime.data.ScreenSessionEntity
import io.github.rudolfhk.screentime.data.SettingsStore
import io.github.rudolfhk.screentime.data.TrackingLogEntity
import io.github.rudolfhk.screentime.data.TrackingPeriodEntity
import io.github.rudolfhk.screentime.data.UnlockEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncResult {
    data class Ok(val events: Int) : SyncResult
    data object Disabled : SyncResult
    data object NoPermission : SyncResult
}

/** Source of raw usage events (the system log in production, a fake in tests). */
fun interface EventSource {
    fun read(from: Long, to: Long): List<RawEvent>
}

/** Periodic background sync (WorkManager in production). */
interface SyncScheduling {
    fun schedule(intervalMinutes: Int)
    fun cancel()
}

/**
 * Owns the tracking lifecycle: on/off, periodic incremental syncs from the system usage log
 * into the local database, gap detection, app metadata, data retention and the tracking log.
 */
class TrackingManager(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val source: EventSource,
    private val appInfo: AppInfoResolver,
    private val scheduler: SyncScheduling,
    private val hasUsageAccess: () -> Boolean,
    private val onDataChanged: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.usageDao()
    private val mutex = Mutex()

    suspend fun enable() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = clock()
            val s = settings.current()
            if (s.trackingEnabled) return@withLock
            // First ever start: backfill from whatever history the system log still holds.
            val backfill = !s.hasEverTracked
            val clipFrom = if (backfill) now - BACKFILL_MS else now
            dao.upsertTrackingPeriod(TrackingPeriodEntity(clipFrom, null, backfilled = backfill))
            settings.startTracking(clipFrom = clipFrom, queryFrom = clipFrom - LOOKBACK_MS, backfill = backfill)
            dao.insertLog(TrackingLogEntity(ts = now, type = LogType.STARTED))
            if (!s.excludedInitialized) settings.setExcluded(appInfo.defaultExclusions())
            scheduler.schedule(s.syncIntervalMinutes)
        }
        sync(SOURCE_START)
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        sync(SOURCE_STOP)
        mutex.withLock {
            val now = clock()
            dao.openTrackingPeriod()?.let { dao.upsertTrackingPeriod(it.copy(end = now)) }
            settings.stopTracking()
            dao.insertLog(TrackingLogEntity(ts = now, type = LogType.STOPPED))
            scheduler.cancel()
        }
        onDataChanged()
    }

    suspend fun reschedule(minutes: Int) {
        settings.setSyncInterval(minutes)
        if (settings.current().trackingEnabled) scheduler.schedule(minutes)
    }

    suspend fun sync(trigger: String = SOURCE_APP): SyncResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock { syncLocked(trigger) }
        if (result is SyncResult.Ok) onDataChanged()
        result
    }

    private suspend fun syncLocked(trigger: String): SyncResult {
        var s = settings.current()
        if (!s.trackingEnabled) return SyncResult.Disabled
        if (!hasUsageAccess()) return SyncResult.NoPermission
        if (!s.periodRepairDone) {
            repairBackfilledPeriod()
            s = settings.current()
        }

        val now = clock()
        val queryFrom = s.queryFrom
        val events = source.read(queryFrom, now)
        val built = SessionBuilder.build(events, clipFrom = s.clipFrom, until = now)
        var clipFrom = s.clipFrom

        if (s.backfillPending) {
            // Only the very first sync after the first start: the tracking period really begins at
            // the oldest event the system still had. Later syncs must never move it again.
            val period = dao.openTrackingPeriod()
            val first = built.firstEventAt
            if (period != null && period.backfilled && first != null && first > period.start) {
                db.withTransaction {
                    dao.deletePeriod(period.start)
                    dao.upsertTrackingPeriod(period.copy(start = first))
                }
                settings.setClipFrom(first)
                clipFrom = first
            }
            dao.insertLog(TrackingLogEntity(ts = now, type = LogType.BACKFILL, value = first ?: clipFrom))
            settings.finishBackfill()
        } else if (now - queryFrom > GAP_CHECK_MS) {
            // We fell behind for days: whatever the system log no longer holds is missing.
            val first = built.firstEventAt ?: now
            if (first - queryFrom > GAP_MIN_MS) {
                val gap = DataGapEntity(maxOf(queryFrom, clipFrom), first)
                dao.insertGap(gap)
                dao.insertLog(TrackingLogEntity(ts = now, type = LogType.GAP, value = gap.end - gap.start, detail = "${gap.start}"))
            }
        }

        db.withTransaction {
            dao.upsertScreenSessions(built.screenSessions.map { ScreenSessionEntity(it.start, it.end, it.unlockAt) })
            dao.upsertAppSessions(built.appSessions.map { AppSessionEntity(it.packageName, it.start, it.end) })
            dao.insertUnlocks(built.unlocks.map { UnlockEntity(it) })
            dao.insertLog(TrackingLogEntity(ts = now, type = LogType.SYNC, value = events.size.toLong(), detail = trigger))
        }
        // Never re-read more than MAX_REREAD_MS: protects against a session that never closes.
        settings.recordSync(queryFrom = maxOf(built.nextQueryFrom, now - MAX_REREAD_MS), at = now)

        resolveNewApps(now)
        dao.pruneSyncLog(now - SYNC_LOG_KEEP_MS)
        if (s.retentionDays > 0) dao.prune(now - s.retentionDays * DAY_MS)
        return SyncResult.Ok(events.size)
    }

    /**
     * Versions up to 1.0 moved the start of a backfilled tracking period forward on every sync,
     * which made coverage, all-time ranges and comparisons look as if tracking had just started.
     * Restores the start to the oldest recorded session. Runs once.
     */
    private suspend fun repairBackfilledPeriod() {
        val period = dao.firstBackfilledPeriod()
        val earliest = dao.firstScreenSessionSince(0)
        if (period != null && earliest != null && earliest < period.start) {
            db.withTransaction {
                dao.deletePeriod(period.start)
                dao.upsertTrackingPeriod(period.copy(start = earliest))
            }
            if (period.end == null) settings.setClipFrom(earliest)
            dao.insertLog(TrackingLogEntity(ts = clock(), type = LogType.REPAIRED, value = earliest))
        }
        settings.markPeriodRepairDone()
    }

    private suspend fun resolveNewApps(now: Long) {
        val known = dao.resolvedPackages().toHashSet()
        val fresh = dao.knownPackages().filter { it !in known }
        if (fresh.isNotEmpty()) dao.upsertApps(fresh.map { appInfo.resolve(it, now) ?: appInfo.fallback(it, now) })
    }

    /** Re-reads labels/categories for all known apps (e.g. after an import from another device). */
    suspend fun refreshAppInfo() = withContext(Dispatchers.IO) {
        val now = clock()
        // Only apps installed here are refreshed; imported labels of others are kept.
        dao.upsertApps(dao.knownPackages().mapNotNull { appInfo.resolve(it, now) })
    }

    suspend fun logImport(records: Int) = withContext(Dispatchers.IO) {
        dao.insertLog(TrackingLogEntity(ts = clock(), type = LogType.IMPORTED, value = records.toLong()))
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = clock()
            dao.clearAll()
            val s = settings.current()
            if (s.trackingEnabled) {
                dao.upsertTrackingPeriod(TrackingPeriodEntity(now, null))
                settings.startTracking(clipFrom = now, queryFrom = now)
            }
            dao.insertLog(TrackingLogEntity(ts = now, type = LogType.CLEARED))
        }
        onDataChanged()
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val SOURCE_APP = "app"
        const val SOURCE_WORKER = "background"
        const val SOURCE_START = "start"
        const val SOURCE_STOP = "stop"
        private const val BACKFILL_MS = 14 * DAY_MS
        private const val LOOKBACK_MS = 3 * 3_600_000L
        private const val GAP_CHECK_MS = 3 * DAY_MS
        private const val GAP_MIN_MS = 12 * 3_600_000L
        private const val MAX_REREAD_MS = 2 * DAY_MS
        private const val SYNC_LOG_KEEP_MS = 30 * DAY_MS
    }
}

/** Production wiring of [SyncScheduling] onto WorkManager. */
class WorkManagerScheduling(private val context: Context) : SyncScheduling {
    override fun schedule(intervalMinutes: Int) = SyncScheduler.schedule(context, intervalMinutes)
    override fun cancel() = SyncScheduler.cancel(context)
}

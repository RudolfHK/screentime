package io.github.rudolfhk.screentime.tracking

import android.content.Context
import androidx.room.withTransaction
import io.github.rudolfhk.screentime.core.SessionBuilder
import io.github.rudolfhk.screentime.data.AppDatabase
import io.github.rudolfhk.screentime.data.AppSessionEntity
import io.github.rudolfhk.screentime.data.DataGapEntity
import io.github.rudolfhk.screentime.data.ScreenSessionEntity
import io.github.rudolfhk.screentime.data.SettingsStore
import io.github.rudolfhk.screentime.data.TrackingPeriodEntity
import io.github.rudolfhk.screentime.data.UnlockEntity
import io.github.rudolfhk.screentime.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncResult {
    data class Ok(val events: Int) : SyncResult
    data object Disabled : SyncResult
    data object NoPermission : SyncResult
}

/**
 * Owns the tracking lifecycle: on/off, periodic incremental syncs from the system usage log
 * into the local database, gap detection, app metadata and data retention.
 */
class TrackingManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val source: UsageEventSource,
    private val appInfo: AppInfoResolver,
    private val widgets: WidgetUpdater,
) {
    private val dao = db.usageDao()
    private val mutex = Mutex()

    suspend fun enable() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val s = settings.current()
            if (s.trackingEnabled) return@withLock
            // First ever start: backfill from whatever history the system log still holds.
            val backfill = !s.hasEverTracked
            val clipFrom = if (backfill) now - BACKFILL_MS else now
            dao.upsertTrackingPeriod(TrackingPeriodEntity(clipFrom, null, backfilled = backfill))
            settings.startTracking(clipFrom = clipFrom, queryFrom = clipFrom - LOOKBACK_MS)
            if (!s.excludedInitialized) settings.setExcluded(appInfo.defaultExclusions())
            SyncScheduler.schedule(context, s.syncIntervalMinutes)
        }
        sync()
    }

    suspend fun disable() = withContext(Dispatchers.IO) {
        sync()
        mutex.withLock {
            val now = System.currentTimeMillis()
            dao.openTrackingPeriod()?.let { dao.upsertTrackingPeriod(it.copy(end = now)) }
            settings.stopTracking()
            SyncScheduler.cancel(context)
        }
        widgets.updateAll()
    }

    suspend fun reschedule(minutes: Int) {
        settings.setSyncInterval(minutes)
        if (settings.current().trackingEnabled) SyncScheduler.schedule(context, minutes)
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock { syncLocked() }
        if (result is SyncResult.Ok) widgets.updateAll()
        result
    }

    private suspend fun syncLocked(): SyncResult {
        val s = settings.current()
        if (!s.trackingEnabled) return SyncResult.Disabled
        if (!Permissions.hasUsageAccess(context)) return SyncResult.NoPermission

        val now = System.currentTimeMillis()
        val queryFrom = s.queryFrom
        val events = source.read(queryFrom, now)
        val built = SessionBuilder.build(events, clipFrom = s.clipFrom, until = now)

        // Backfill: the tracking period really starts at the oldest event the system still had.
        val period = dao.openTrackingPeriod()
        var clipFrom = s.clipFrom
        val firstBackfillSync = period != null && period.backfilled && period.start == s.clipFrom
        if (period != null && firstBackfillSync) {
            val first = built.firstEventAt
            if (first != null && first > period.start) {
                db.withTransaction {
                    dao.deletePeriod(period.start)
                    dao.upsertTrackingPeriod(period.copy(start = first))
                }
                settings.setClipFrom(first)
                clipFrom = first
            }
        }

        // Gap detection: if we fell behind the system's retention window, data is missing.
        val sinceLast = now - queryFrom
        if (!firstBackfillSync && sinceLast > GAP_CHECK_MS) {
            val first = built.firstEventAt ?: now
            if (first - queryFrom > GAP_MIN_MS) {
                dao.insertGap(DataGapEntity(maxOf(queryFrom, clipFrom), first))
            }
        }

        db.withTransaction {
            dao.upsertScreenSessions(built.screenSessions.map { ScreenSessionEntity(it.start, it.end, it.unlockAt) })
            dao.upsertAppSessions(built.appSessions.map { AppSessionEntity(it.packageName, it.start, it.end) })
            dao.insertUnlocks(built.unlocks.map { UnlockEntity(it) })
        }
        // Never re-read more than MAX_REREAD_MS: protects against a session that never closes.
        settings.recordSync(queryFrom = maxOf(built.nextQueryFrom, now - MAX_REREAD_MS), at = now)

        resolveNewApps(now)
        if (s.retentionDays > 0) dao.prune(now - s.retentionDays * DAY_MS)
        return SyncResult.Ok(events.size)
    }

    private suspend fun resolveNewApps(now: Long) {
        val known = dao.resolvedPackages().toHashSet()
        val fresh = dao.knownPackages().filter { it !in known }
        if (fresh.isNotEmpty()) dao.upsertApps(fresh.map { appInfo.resolve(it, now) ?: appInfo.fallback(it, now) })
    }

    /** Re-reads labels/categories for all known apps (e.g. after an import from another device). */
    suspend fun refreshAppInfo() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Only apps installed here are refreshed; imported labels of others are kept.
        dao.upsertApps(dao.knownPackages().mapNotNull { appInfo.resolve(it, now) })
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            dao.clearAll()
            val s = settings.current()
            if (s.trackingEnabled) {
                dao.upsertTrackingPeriod(TrackingPeriodEntity(now, null))
                settings.startTracking(clipFrom = now, queryFrom = now)
            }
        }
        widgets.updateAll()
    }

    companion object {
        const val DAY_MS = 86_400_000L
        private const val BACKFILL_MS = 14 * DAY_MS
        private const val LOOKBACK_MS = 3 * 3_600_000L
        private const val GAP_CHECK_MS = 3 * DAY_MS
        private const val GAP_MIN_MS = 12 * 3_600_000L
        private const val MAX_REREAD_MS = 2 * DAY_MS
    }
}

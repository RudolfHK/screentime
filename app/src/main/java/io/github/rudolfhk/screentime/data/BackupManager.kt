package io.github.rudolfhk.screentime.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.rudolfhk.screentime.BuildConfig
import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.core.BackupApp
import io.github.rudolfhk.screentime.core.BackupAppSession
import io.github.rudolfhk.screentime.core.BackupBundle
import io.github.rudolfhk.screentime.core.BackupCodec
import io.github.rudolfhk.screentime.core.BackupGap
import io.github.rudolfhk.screentime.core.BackupNotification
import io.github.rudolfhk.screentime.core.BackupScreenSession
import io.github.rudolfhk.screentime.core.BackupSettings
import io.github.rudolfhk.screentime.core.BackupTrackingPeriod
import io.github.rudolfhk.screentime.core.Csv
import io.github.rudolfhk.screentime.core.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImportSummary(
    val screenSessions: Int,
    val appSessions: Int,
    val unlocks: Int,
    val notifications: Int,
    val periods: Int,
) {
    val total get() = screenSessions + appSessions + unlocks + notifications + periods
}

/**
 * Manual backup via the Storage Access Framework (no storage permission needed).
 * JSON = full, versioned, re-importable backup. ZIP of CSVs = for spreadsheets.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val stats: StatsRepository,
) {
    private val dao = db.usageDao()

    suspend fun exportJson(uri: Uri): Int = withContext(Dispatchers.IO) {
        val s = settings.current()
        val bundle = BackupBundle(
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            trackingPeriods = dao.allTrackingPeriods().map { BackupTrackingPeriod(it.start, it.end, it.backfilled) },
            dataGaps = dao.allGaps().map { BackupGap(it.start, it.end) },
            screenSessions = dao.allScreenSessions().map { BackupScreenSession(it.start, it.end, it.unlockAt) },
            unlocks = dao.allUnlocks(),
            appSessions = dao.allAppSessions().map { BackupAppSession(it.packageName, it.start, it.end) },
            notifications = dao.allNotifications().map { BackupNotification(it.packageName, it.ts) },
            apps = dao.allApps().map { BackupApp(it.packageName, it.label, it.category) },
            settings = BackupSettings(s.dailyGoalMinutes, s.excludedPackages.sorted()),
        )
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open file")
        out.bufferedWriter().use { it.write(BackupCodec.encode(bundle)) }
        bundle.screenSessions.size + bundle.appSessions.size + bundle.unlocks.size + bundle.notifications.size
    }

    suspend fun exportCsvZip(uri: Uri) = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun t(ts: Long?) = ts?.let { iso.format(Instant.ofEpochMilli(it).atZone(zone)) }
        val apps = dao.allApps().associateBy { it.packageName }
        val all = stats.allTime()
        val screens = dao.allScreenSessions()
        val appSessions = dao.allAppSessions()
        val unlocks = dao.allUnlocks()
        val notifications = dao.allNotifications()

        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open file")
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, block: (StringBuilder) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val sb = StringBuilder()
                block(sb)
                zip.write(sb.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("daily_summary.csv") { sb ->
                sb.append(Csv.row("date", "screen_time_min", "unlocks", "first_use", "last_use"))
                all.days.forEach { d ->
                    sb.append(
                        Csv.row(
                            d.date, d.screenTime / 60_000, d.unlocks,
                            d.firstUseMinute?.let(Format::minuteOfDay), d.lastUseMinute?.let(Format::minuteOfDay),
                        ),
                    )
                }
            }
            entry("app_totals.csv") { sb ->
                sb.append(Csv.row("package", "label", "category", "total_min", "launches", "avg_session_s", "notifications"))
                all.apps.forEach { a ->
                    sb.append(
                        Csv.row(
                            a.packageName, apps[a.packageName]?.label, AppCategories.label(a.category),
                            a.total / 60_000, a.launches, a.avgSession / 1000, a.notifications,
                        ),
                    )
                }
            }
            entry("screen_sessions.csv") { sb ->
                sb.append(Csv.row("screen_on", "unlocked_at", "screen_off", "duration_s", "unlocked"))
                screens.forEach {
                    sb.append(Csv.row(t(it.start), t(it.unlockAt), t(it.end), (it.end - it.start) / 1000, it.unlockAt != null))
                }
            }
            entry("app_sessions.csv") { sb ->
                sb.append(Csv.row("package", "label", "start", "end", "duration_s"))
                appSessions.forEach {
                    sb.append(Csv.row(it.packageName, apps[it.packageName]?.label, t(it.start), t(it.end), (it.end - it.start) / 1000))
                }
            }
            entry("unlocks.csv") { sb ->
                sb.append(Csv.row("unlocked_at"))
                unlocks.forEach { sb.append(Csv.row(t(it))) }
            }
            entry("notifications.csv") { sb ->
                sb.append(Csv.row("package", "label", "posted_at"))
                notifications.forEach { sb.append(Csv.row(it.packageName, apps[it.packageName]?.label, t(it.ts))) }
            }
        }
    }

    /** Merges a backup into the local database. Existing rows always win (dedupe by natural key). */
    suspend fun import(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Cannot read file")
        val b = BackupCodec.decode(text)
        val now = System.currentTimeMillis()
        val summary = db.withTransaction {
            ImportSummary(
                screenSessions = dao.importScreenSessions(b.screenSessions.map { ScreenSessionEntity(it.start, it.end, it.unlockAt) }).inserted(),
                appSessions = dao.importAppSessions(b.appSessions.map { AppSessionEntity(it.packageName, it.start, it.end) }).inserted(),
                unlocks = dao.importUnlocks(b.unlocks.map { UnlockEntity(it) }).inserted(),
                notifications = dao.importNotifications(b.notifications.map { NotificationEntity(it.packageName, it.ts) }).inserted(),
                periods = dao.importTrackingPeriods(
                    // Imported periods are closed history; the open one on this device stays the only open one.
                    b.trackingPeriods.map { TrackingPeriodEntity(it.start, it.end ?: b.exportedAt, it.backfilled) },
                ).inserted(),
            ).also {
                dao.importGaps(b.dataGaps.map { DataGapEntity(it.start, it.end) })
                dao.importApps(b.apps.map { AppInfoEntity(it.packageName, it.label, it.category, now) })
            }
        }
        b.settings?.let { imported ->
            val s = settings.current()
            if (s.dailyGoalMinutes == 0 && imported.dailyGoalMinutes > 0) settings.setDailyGoal(imported.dailyGoalMinutes)
            if (imported.excludedPackages.isNotEmpty()) settings.setExcluded(s.excludedPackages + imported.excludedPackages)
        }
        summary
    }

    private fun List<Long>.inserted() = count { it != -1L }
}

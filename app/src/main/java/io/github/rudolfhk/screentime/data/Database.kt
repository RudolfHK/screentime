package io.github.rudolfhk.screentime.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------------------------
// Entities. Natural keys (start timestamps) make re-syncs and imports idempotent.
// ---------------------------------------------------------------------------------------------

@Entity(tableName = "screen_sessions")
data class ScreenSessionEntity(
    @PrimaryKey @ColumnInfo(name = "start_ts") val start: Long,
    @ColumnInfo(name = "end_ts", index = true) val end: Long,
    @ColumnInfo(name = "unlock_ts") val unlockAt: Long?,
)

@Entity(tableName = "unlocks")
data class UnlockEntity(
    @PrimaryKey val ts: Long,
)

@Entity(
    tableName = "app_sessions",
    primaryKeys = ["pkg", "start_ts"],
    indices = [Index("start_ts"), Index("end_ts")],
)
data class AppSessionEntity(
    @ColumnInfo(name = "pkg") val packageName: String,
    @ColumnInfo(name = "start_ts") val start: Long,
    @ColumnInfo(name = "end_ts") val end: Long,
)

@Entity(tableName = "notifications", primaryKeys = ["pkg", "ts"], indices = [Index("ts")])
data class NotificationEntity(
    @ColumnInfo(name = "pkg") val packageName: String,
    val ts: Long,
)

@Entity(tableName = "apps")
data class AppInfoEntity(
    @PrimaryKey @ColumnInfo(name = "pkg") val packageName: String,
    val label: String,
    val category: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "tracking_periods")
data class TrackingPeriodEntity(
    @PrimaryKey @ColumnInfo(name = "start_ts") val start: Long,
    @ColumnInfo(name = "end_ts") val end: Long?,
    val backfilled: Boolean = false,
)

@Entity(tableName = "data_gaps")
data class DataGapEntity(
    @PrimaryKey @ColumnInfo(name = "start_ts") val start: Long,
    @ColumnInfo(name = "end_ts") val end: Long,
)

data class AppSessionRow(
    @ColumnInfo(name = "pkg") val packageName: String,
    @ColumnInfo(name = "start_ts") val start: Long,
    @ColumnInfo(name = "end_ts") val end: Long,
)

// ---------------------------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------------------------

@Dao
interface UsageDao {

    // --- writes from sync (REPLACE: provisional sessions get their final end time) ---

    @Upsert
    suspend fun upsertScreenSessions(rows: List<ScreenSessionEntity>)

    @Upsert
    suspend fun upsertAppSessions(rows: List<AppSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlocks(rows: List<UnlockEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(row: NotificationEntity)

    @Upsert
    suspend fun upsertApps(rows: List<AppInfoEntity>)

    @Upsert
    suspend fun upsertTrackingPeriod(row: TrackingPeriodEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGap(row: DataGapEntity)

    @Query("DELETE FROM tracking_periods WHERE start_ts = :start")
    suspend fun deletePeriod(start: Long)

    // --- import (IGNORE: never overwrite what is already on this device) ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importScreenSessions(rows: List<ScreenSessionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importAppSessions(rows: List<AppSessionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importUnlocks(rows: List<UnlockEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importNotifications(rows: List<NotificationEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importApps(rows: List<AppInfoEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importTrackingPeriods(rows: List<TrackingPeriodEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importGaps(rows: List<DataGapEntity>): List<Long>

    // --- range reads (overlap semantics) ---

    @Query("SELECT * FROM screen_sessions WHERE end_ts > :from AND start_ts < :to ORDER BY start_ts")
    fun screenSessions(from: Long, to: Long): Flow<List<ScreenSessionEntity>>

    @Query("SELECT pkg, start_ts, end_ts FROM app_sessions WHERE end_ts > :from AND start_ts < :to ORDER BY start_ts")
    fun appSessions(from: Long, to: Long): Flow<List<AppSessionRow>>

    @Query("SELECT ts FROM unlocks WHERE ts >= :from AND ts < :to ORDER BY ts")
    fun unlocks(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT * FROM notifications WHERE ts >= :from AND ts < :to")
    fun notifications(from: Long, to: Long): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM tracking_periods ORDER BY start_ts")
    fun trackingPeriods(): Flow<List<TrackingPeriodEntity>>

    @Query("SELECT * FROM data_gaps ORDER BY start_ts")
    fun gaps(): Flow<List<DataGapEntity>>

    @Query("SELECT * FROM apps")
    fun apps(): Flow<List<AppInfoEntity>>

    @Query("SELECT * FROM tracking_periods WHERE end_ts IS NULL ORDER BY start_ts DESC LIMIT 1")
    suspend fun openTrackingPeriod(): TrackingPeriodEntity?

    @Query("SELECT MIN(start_ts) FROM tracking_periods")
    fun firstTrackedAt(): Flow<Long?>

    @Query("SELECT DISTINCT pkg FROM app_sessions")
    suspend fun knownPackages(): List<String>

    @Query("SELECT pkg FROM apps")
    suspend fun resolvedPackages(): List<String>

    // --- full dumps for export ---

    @Query("SELECT * FROM screen_sessions ORDER BY start_ts") suspend fun allScreenSessions(): List<ScreenSessionEntity>
    @Query("SELECT * FROM app_sessions ORDER BY start_ts") suspend fun allAppSessions(): List<AppSessionEntity>
    @Query("SELECT ts FROM unlocks ORDER BY ts") suspend fun allUnlocks(): List<Long>
    @Query("SELECT * FROM notifications ORDER BY ts") suspend fun allNotifications(): List<NotificationEntity>
    @Query("SELECT * FROM apps") suspend fun allApps(): List<AppInfoEntity>
    @Query("SELECT * FROM tracking_periods ORDER BY start_ts") suspend fun allTrackingPeriods(): List<TrackingPeriodEntity>
    @Query("SELECT * FROM data_gaps ORDER BY start_ts") suspend fun allGaps(): List<DataGapEntity>

    // --- retention ---

    @Query("DELETE FROM screen_sessions WHERE end_ts < :before") suspend fun pruneScreen(before: Long)
    @Query("DELETE FROM app_sessions WHERE end_ts < :before") suspend fun pruneApps(before: Long)
    @Query("DELETE FROM unlocks WHERE ts < :before") suspend fun pruneUnlocks(before: Long)
    @Query("DELETE FROM notifications WHERE ts < :before") suspend fun pruneNotifications(before: Long)
    @Query("DELETE FROM data_gaps WHERE end_ts < :before") suspend fun pruneGaps(before: Long)
    @Query("UPDATE tracking_periods SET start_ts = :before WHERE start_ts < :before AND (end_ts IS NULL OR end_ts >= :before)")
    suspend fun clampPeriods(before: Long)
    @Query("DELETE FROM tracking_periods WHERE end_ts IS NOT NULL AND end_ts < :before") suspend fun prunePeriods(before: Long)

    @Transaction
    suspend fun prune(before: Long) {
        pruneScreen(before); pruneApps(before); pruneUnlocks(before)
        pruneNotifications(before); pruneGaps(before); prunePeriods(before); clampPeriods(before)
    }

    @Query("DELETE FROM screen_sessions") suspend fun clearScreen()
    @Query("DELETE FROM app_sessions") suspend fun clearApps()
    @Query("DELETE FROM unlocks") suspend fun clearUnlocks()
    @Query("DELETE FROM notifications") suspend fun clearNotifications()
    @Query("DELETE FROM tracking_periods") suspend fun clearPeriods()
    @Query("DELETE FROM data_gaps") suspend fun clearGaps()

    @Transaction
    suspend fun clearAll() {
        clearScreen(); clearApps(); clearUnlocks(); clearNotifications(); clearPeriods(); clearGaps()
    }
}

@Database(
    entities = [
        ScreenSessionEntity::class, UnlockEntity::class, AppSessionEntity::class, NotificationEntity::class,
        AppInfoEntity::class, TrackingPeriodEntity::class, DataGapEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "screentime.db").build()
    }
}

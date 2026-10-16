package io.github.rudolfhk.screentime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val trackingEnabled: Boolean = false,
    /** Next sync reads the system log from here (start of anything still open). */
    val queryFrom: Long = 0,
    /** Start of the current tracking period; nothing earlier is recorded. */
    val clipFrom: Long = 0,
    val lastSyncAt: Long = 0,
    val syncIntervalMinutes: Int = 30,
    /** 0 = keep forever. */
    val retentionDays: Int = 0,
    /** 0 = no goal. */
    val dailyGoalMinutes: Int = 0,
    val excludedPackages: Set<String> = emptySet(),
    val excludedInitialized: Boolean = false,
    val countNotifications: Boolean = false,
    val appLock: Boolean = false,
    val hasEverTracked: Boolean = false,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val trackingEnabled = booleanPreferencesKey("tracking_enabled")
        val queryFrom = longPreferencesKey("query_from")
        val clipFrom = longPreferencesKey("clip_from")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val syncInterval = intPreferencesKey("sync_interval_min")
        val retentionDays = intPreferencesKey("retention_days")
        val dailyGoal = intPreferencesKey("daily_goal_min")
        val excluded = stringSetPreferencesKey("excluded_packages")
        val excludedInit = booleanPreferencesKey("excluded_initialized")
        val countNotifications = booleanPreferencesKey("count_notifications")
        val appLock = booleanPreferencesKey("app_lock")
        val hasEverTracked = booleanPreferencesKey("has_ever_tracked")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            trackingEnabled = p[Keys.trackingEnabled] ?: false,
            queryFrom = p[Keys.queryFrom] ?: 0,
            clipFrom = p[Keys.clipFrom] ?: 0,
            lastSyncAt = p[Keys.lastSyncAt] ?: 0,
            syncIntervalMinutes = p[Keys.syncInterval] ?: 30,
            retentionDays = p[Keys.retentionDays] ?: 0,
            dailyGoalMinutes = p[Keys.dailyGoal] ?: 0,
            excludedPackages = p[Keys.excluded] ?: emptySet(),
            excludedInitialized = p[Keys.excludedInit] ?: false,
            countNotifications = p[Keys.countNotifications] ?: false,
            appLock = p[Keys.appLock] ?: false,
            hasEverTracked = p[Keys.hasEverTracked] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun startTracking(clipFrom: Long, queryFrom: Long) = context.dataStore.edit {
        it[Keys.trackingEnabled] = true
        it[Keys.clipFrom] = clipFrom
        it[Keys.queryFrom] = queryFrom
        it[Keys.hasEverTracked] = true
    }

    suspend fun stopTracking() = context.dataStore.edit { it[Keys.trackingEnabled] = false }

    suspend fun recordSync(queryFrom: Long, at: Long) = context.dataStore.edit {
        it[Keys.queryFrom] = queryFrom
        it[Keys.lastSyncAt] = at
    }

    suspend fun setClipFrom(value: Long) = context.dataStore.edit { it[Keys.clipFrom] = value }
    suspend fun setSyncInterval(minutes: Int) = context.dataStore.edit { it[Keys.syncInterval] = minutes }
    suspend fun setRetentionDays(days: Int) = context.dataStore.edit { it[Keys.retentionDays] = days }
    suspend fun setDailyGoal(minutes: Int) = context.dataStore.edit { it[Keys.dailyGoal] = minutes }
    suspend fun setCountNotifications(on: Boolean) = context.dataStore.edit { it[Keys.countNotifications] = on }
    suspend fun setAppLock(on: Boolean) = context.dataStore.edit { it[Keys.appLock] = on }

    suspend fun setExcluded(packages: Set<String>) = context.dataStore.edit {
        it[Keys.excluded] = packages
        it[Keys.excludedInit] = true
    }
}

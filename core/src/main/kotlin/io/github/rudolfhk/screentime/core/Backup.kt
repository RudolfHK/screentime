package io.github.rudolfhk.screentime.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Versioned, self-describing backup format. Bump [CURRENT_VERSION] on incompatible changes and
 * keep [BackupCodec.decode] able to read older versions.
 */
@Serializable
data class BackupBundle(
    val format: String = FORMAT_ID,
    val schemaVersion: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val appVersion: String = "",
    val trackingPeriods: List<BackupTrackingPeriod> = emptyList(),
    val dataGaps: List<BackupGap> = emptyList(),
    val screenSessions: List<BackupScreenSession> = emptyList(),
    val unlocks: List<Long> = emptyList(),
    val appSessions: List<BackupAppSession> = emptyList(),
    val notifications: List<BackupNotification> = emptyList(),
    val apps: List<BackupApp> = emptyList(),
    val settings: BackupSettings? = null,
) {
    companion object {
        const val FORMAT_ID = "screentime-diagnostics-backup"
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackupTrackingPeriod(val start: Long, val end: Long? = null, val backfilled: Boolean = false)

@Serializable
data class BackupGap(val start: Long, val end: Long)

@Serializable
data class BackupScreenSession(val start: Long, val end: Long, val unlockAt: Long? = null)

@Serializable
data class BackupAppSession(
    @SerialName("pkg") val packageName: String,
    val start: Long,
    val end: Long,
)

@Serializable
data class BackupNotification(@SerialName("pkg") val packageName: String, val ts: Long)

@Serializable
data class BackupApp(@SerialName("pkg") val packageName: String, val label: String, val category: Int = -1)

@Serializable
data class BackupSettings(
    val dailyGoalMinutes: Int = 0,
    val excludedPackages: List<String> = emptyList(),
)

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    fun decode(text: String): BackupBundle {
        val bundle = try {
            json.decodeFromString(BackupBundle.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException("Not a valid backup file", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a valid backup file", e)
        }
        if (bundle.format != BackupBundle.FORMAT_ID) throw BackupFormatException("Unknown file format '${bundle.format}'")
        if (bundle.schemaVersion > BackupBundle.CURRENT_VERSION) {
            throw BackupFormatException("Backup was made by a newer app version (schema ${bundle.schemaVersion}). Please update the app.")
        }
        return sanitize(bundle)
    }

    /** Drops obviously corrupt rows instead of failing the whole import. */
    private fun sanitize(b: BackupBundle) = b.copy(
        screenSessions = b.screenSessions.filter { it.end > it.start && (it.unlockAt == null || it.unlockAt in it.start..it.end) },
        appSessions = b.appSessions.filter { it.end > it.start && it.packageName.isNotBlank() },
        trackingPeriods = b.trackingPeriods.filter { it.end == null || it.end >= it.start },
        dataGaps = b.dataGaps.filter { it.end > it.start },
        unlocks = b.unlocks.filter { it > 0 },
        notifications = b.notifications.filter { it.packageName.isNotBlank() },
    )
}

/** Minimal RFC 4180 CSV writer for the spreadsheet export. */
object Csv {
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun row(vararg values: Any?): String = values.joinToString(",") { escape(it?.toString() ?: "") } + "\r\n"
}

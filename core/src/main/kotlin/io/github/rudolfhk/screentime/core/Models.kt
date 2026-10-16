package io.github.rudolfhk.screentime.core

/**
 * Platform-independent view of a usage event. The Android layer maps
 * `UsageEvents.Event` types onto these so all session logic stays testable on the JVM.
 */
data class RawEvent(
    val type: EventType,
    val timestamp: Long,
    val packageName: String? = null,
)

enum class EventType {
    SCREEN_ON,
    SCREEN_OFF,
    /** Keyguard dismissed – the user unlocked the device. */
    UNLOCK,
    APP_RESUMED,
    APP_PAUSED,
    SHUTDOWN,
    STARTUP,
}

/**
 * One continuous period with the display on.
 * [unlockAt] is the first unlock within the session, or null if the screen was only woken
 * (a "glance" at the lock screen / notifications).
 */
data class ScreenSession(
    val start: Long,
    val end: Long,
    val unlockAt: Long?,
) {
    val unlocked: Boolean get() = unlockAt != null
}

data class AppSession(
    val packageName: String,
    val start: Long,
    val end: Long,
)

data class NotificationEvent(
    val packageName: String,
    val timestamp: Long,
)

/** Period during which tracking was switched on. [end] is null while still active. */
data class TrackingPeriod(
    val start: Long,
    val end: Long?,
    val backfilled: Boolean = false,
)

/** A span where tracking was on but no data could be collected (system log expired). */
data class DataGap(
    val start: Long,
    val end: Long,
)

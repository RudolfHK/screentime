package io.github.rudolfhk.screentime.tracking

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import io.github.rudolfhk.screentime.core.EventType
import io.github.rudolfhk.screentime.core.RawEvent

/**
 * Reads the system usage log. Android records these events anyway, so polling it in batches
 * costs almost nothing compared to running our own always-on service.
 */
class UsageEventSource(context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)

    fun read(from: Long, to: Long): List<RawEvent> {
        if (to <= from) return emptyList()
        val events = usm?.queryEvents(from, to) ?: return emptyList()
        val e = UsageEvents.Event()
        val out = ArrayList<RawEvent>(1024)
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            val type = map(e.eventType) ?: continue
            out += RawEvent(type, e.timeStamp, if (type == EventType.APP_RESUMED || type == EventType.APP_PAUSED) e.packageName else null)
        }
        return out
    }

    private fun map(type: Int): EventType? = when (type) {
        ACTIVITY_RESUMED -> EventType.APP_RESUMED
        ACTIVITY_PAUSED -> EventType.APP_PAUSED
        SCREEN_INTERACTIVE -> EventType.SCREEN_ON
        SCREEN_NON_INTERACTIVE -> EventType.SCREEN_OFF
        KEYGUARD_HIDDEN -> EventType.UNLOCK
        DEVICE_SHUTDOWN -> EventType.SHUTDOWN
        DEVICE_STARTUP -> EventType.STARTUP
        else -> null
    }

    private companion object {
        // Literal values of UsageEvents.Event constants (some are named differently below API 29).
        const val ACTIVITY_RESUMED = 1 // MOVE_TO_FOREGROUND on API 28
        const val ACTIVITY_PAUSED = 2 // MOVE_TO_BACKGROUND on API 28
        const val SCREEN_INTERACTIVE = 15
        const val SCREEN_NON_INTERACTIVE = 16
        const val KEYGUARD_HIDDEN = 18
        const val DEVICE_SHUTDOWN = 26
        const val DEVICE_STARTUP = 27
    }
}

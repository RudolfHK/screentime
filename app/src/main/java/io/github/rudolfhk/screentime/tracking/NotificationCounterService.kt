package io.github.rudolfhk.screentime.tracking

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.rudolfhk.screentime.ScreenTimeApp
import io.github.rudolfhk.screentime.data.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opt-in notification counter. Only the package name and post time are stored – never titles
 * or content. The system binds this service only after the user grants notification access.
 */
class NotificationCounterService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsJob: Job? = null

    @Volatile private var active = false

    /** key → `when` of the last counted post, so plain updates of a notification aren't recounted. */
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 500
    }

    override fun onListenerConnected() {
        val container = (application as ScreenTimeApp).container
        settingsJob?.cancel()
        settingsJob = scope.launch {
            container.settings.settings.collect { active = it.trackingEnabled && it.countNotifications }
        }
    }

    override fun onListenerDisconnected() {
        settingsJob?.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!active) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        synchronized(seen) {
            if (seen[sbn.key] == n.`when`) return
            seen[sbn.key] = n.`when`
        }
        val dao = (application as ScreenTimeApp).container.db.usageDao()
        scope.launch { dao.insertNotification(NotificationEntity(sbn.packageName, sbn.postTime)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

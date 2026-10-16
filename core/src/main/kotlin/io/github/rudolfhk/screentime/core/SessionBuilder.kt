package io.github.rudolfhk.screentime.core

/**
 * Turns a raw stream of usage events into screen sessions, app sessions and unlocks.
 *
 * Sync model: every sync re-reads the system log from [BuildResult.nextQueryFrom] of the previous
 * run (the start of anything that was still open), so open sessions are emitted with a provisional
 * end of `until` and simply get replaced by the next sync (they are keyed by start time).
 */
object SessionBuilder {

    data class BuildResult(
        val screenSessions: List<ScreenSession>,
        val appSessions: List<AppSession>,
        val unlocks: List<Long>,
        /** Where the next sync should start reading so open sessions are rebuilt completely. */
        val nextQueryFrom: Long,
        /** Timestamp of the earliest event seen, or null if none. */
        val firstEventAt: Long?,
    )

    /**
     * @param events events in any order; they are sorted by timestamp (stable).
     * @param clipFrom nothing before this instant is reported (start of the tracking period).
     * @param until the instant the log was read up to; open sessions end here provisionally.
     */
    fun build(events: List<RawEvent>, clipFrom: Long, until: Long): BuildResult {
        val sorted = events.filter { it.timestamp <= until }.sortedBy { it.timestamp }

        val screens = ArrayList<ScreenSession>()
        val apps = ArrayList<AppSession>()
        val unlocks = ArrayList<Long>()

        var screenStart: Long? = null
        var unlockAt: Long? = null
        var currentApp: String? = null
        var appStart = 0L
        var pendingPause: Long? = null
        var lastEventTs: Long? = null

        fun closeApp(at: Long) {
            val pkg = currentApp ?: return
            val end = minOf(pendingPause ?: at, at)
            if (end > appStart) apps += AppSession(pkg, appStart, end)
            currentApp = null
            pendingPause = null
        }

        fun closeScreen(at: Long) {
            val start = screenStart ?: return
            if (at > start) screens += ScreenSession(start, at, unlockAt)
            screenStart = null
            unlockAt = null
        }

        for (e in sorted) {
            val ts = e.timestamp
            when (e.type) {
                EventType.SCREEN_ON -> {
                    // A missing SCREEN_OFF means we lost an event; end the old session here.
                    closeApp(ts)
                    closeScreen(ts)
                    screenStart = ts
                }
                EventType.UNLOCK -> {
                    unlocks += ts
                    if (screenStart == null) screenStart = ts
                    if (unlockAt == null) unlockAt = ts
                }
                EventType.APP_RESUMED -> {
                    val pkg = e.packageName ?: continue
                    if (screenStart == null) {
                        // Log window started mid-session: an app in the foreground implies usage.
                        screenStart = ts
                        unlockAt = ts
                    }
                    if (currentApp == pkg) {
                        pendingPause = null // activity switch inside the same app
                    } else {
                        closeApp(ts)
                        currentApp = pkg
                        appStart = ts
                    }
                }
                EventType.APP_PAUSED -> {
                    if (e.packageName != null && e.packageName == currentApp) pendingPause = ts
                }
                EventType.SCREEN_OFF, EventType.SHUTDOWN -> {
                    closeApp(ts)
                    closeScreen(ts)
                }
                EventType.STARTUP -> {
                    // Device rebooted without a shutdown event: close at the last thing we saw.
                    val at = lastEventTs ?: ts
                    closeApp(at)
                    closeScreen(at)
                }
            }
            lastEventTs = ts
        }

        // Open sessions at the end of the window are provisional.
        var nextQueryFrom = until
        screenStart?.let { nextQueryFrom = minOf(nextQueryFrom, it) }
        if (currentApp != null) nextQueryFrom = minOf(nextQueryFrom, appStart)
        if (currentApp != null) {
            val end = pendingPause ?: until
            if (end > appStart) apps += AppSession(currentApp!!, appStart, end)
        }
        screenStart?.let { start -> if (until > start) screens += ScreenSession(start, until, unlockAt) }

        return BuildResult(
            screenSessions = screens.mapNotNull { it.clip(clipFrom) },
            appSessions = apps.mapNotNull { it.clip(clipFrom) },
            unlocks = unlocks.filter { it >= clipFrom },
            nextQueryFrom = nextQueryFrom,
            firstEventAt = sorted.firstOrNull()?.timestamp,
        )
    }

    private fun ScreenSession.clip(from: Long): ScreenSession? {
        if (end <= from) return null
        if (start >= from) return this
        return copy(start = from, unlockAt = unlockAt?.let { maxOf(it, from) })
    }

    private fun AppSession.clip(from: Long): AppSession? {
        if (end <= from) return null
        return if (start >= from) this else copy(start = from)
    }
}

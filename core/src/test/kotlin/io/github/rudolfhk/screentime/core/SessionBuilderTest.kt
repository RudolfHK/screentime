package io.github.rudolfhk.screentime.core

import io.github.rudolfhk.screentime.core.EventType.APP_PAUSED
import io.github.rudolfhk.screentime.core.EventType.APP_RESUMED
import io.github.rudolfhk.screentime.core.EventType.SCREEN_OFF
import io.github.rudolfhk.screentime.core.EventType.SCREEN_ON
import io.github.rudolfhk.screentime.core.EventType.SHUTDOWN
import io.github.rudolfhk.screentime.core.EventType.STARTUP
import io.github.rudolfhk.screentime.core.EventType.UNLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionBuilderTest {

    private fun ev(type: EventType, ts: Long, pkg: String? = null) = RawEvent(type, ts, pkg)

    @Test
    fun `basic unlock and app usage`() {
        val r = SessionBuilder.build(
            listOf(
                ev(SCREEN_ON, 1000),
                ev(UNLOCK, 2000),
                ev(APP_RESUMED, 2500, "a"),
                ev(APP_PAUSED, 5000, "a"),
                ev(APP_RESUMED, 5100, "b"),
                ev(APP_PAUSED, 8000, "b"),
                ev(SCREEN_OFF, 8100),
            ),
            clipFrom = 0, until = 10_000,
        )
        assertEquals(listOf(ScreenSession(1000, 8100, 2000)), r.screenSessions)
        assertEquals(listOf(AppSession("a", 2500, 5000), AppSession("b", 5100, 8000)), r.appSessions)
        assertEquals(listOf(2000L), r.unlocks)
        assertEquals(10_000L, r.nextQueryFrom)
    }

    @Test
    fun `activity switch inside same app stays one session`() {
        val r = SessionBuilder.build(
            listOf(
                ev(SCREEN_ON, 0), ev(UNLOCK, 0),
                ev(APP_RESUMED, 100, "a"), ev(APP_PAUSED, 200, "a"),
                ev(APP_RESUMED, 210, "a"), ev(APP_PAUSED, 900, "a"),
                ev(SCREEN_OFF, 1000),
            ),
            clipFrom = 0, until = 2000,
        )
        assertEquals(listOf(AppSession("a", 100, 900)), r.appSessions)
    }

    @Test
    fun `glance without unlock`() {
        val r = SessionBuilder.build(listOf(ev(SCREEN_ON, 0), ev(SCREEN_OFF, 3000)), 0, 5000)
        assertEquals(1, r.screenSessions.size)
        assertNull(r.screenSessions[0].unlockAt)
        assertEquals(0, r.unlocks.size)
    }

    @Test
    fun `open sessions are provisional and set next query start`() {
        val r = SessionBuilder.build(
            listOf(ev(SCREEN_ON, 100), ev(UNLOCK, 150), ev(APP_RESUMED, 200, "a")),
            clipFrom = 0, until = 1000,
        )
        assertEquals(listOf(ScreenSession(100, 1000, 150)), r.screenSessions)
        assertEquals(listOf(AppSession("a", 200, 1000)), r.appSessions)
        assertEquals(100L, r.nextQueryFrom)

        // Re-running later from nextQueryFrom yields the same keys with the final end.
        val r2 = SessionBuilder.build(
            listOf(ev(SCREEN_ON, 100), ev(UNLOCK, 150), ev(APP_RESUMED, 200, "a"), ev(SCREEN_OFF, 1500)),
            clipFrom = 0, until = 2000,
        )
        assertEquals(listOf(ScreenSession(100, 1500, 150)), r2.screenSessions)
        assertEquals(listOf(AppSession("a", 200, 1500)), r2.appSessions)
        assertEquals(2000L, r2.nextQueryFrom)
    }

    @Test
    fun `clipping to tracking start`() {
        val r = SessionBuilder.build(
            listOf(ev(SCREEN_ON, 0), ev(UNLOCK, 10), ev(APP_RESUMED, 20, "a"), ev(SCREEN_OFF, 500)),
            clipFrom = 100, until = 1000,
        )
        assertEquals(listOf(ScreenSession(100, 500, 100)), r.screenSessions)
        assertEquals(listOf(AppSession("a", 100, 500)), r.appSessions)
        assertEquals(emptyList<Long>(), r.unlocks)
    }

    @Test
    fun `missing screen off closes at next screen on`() {
        val r = SessionBuilder.build(
            listOf(ev(SCREEN_ON, 0), ev(UNLOCK, 1), ev(SCREEN_ON, 100), ev(SCREEN_OFF, 200)),
            0, 1000,
        )
        assertEquals(listOf(ScreenSession(0, 100, 1), ScreenSession(100, 200, null)), r.screenSessions)
    }

    @Test
    fun `reboot without shutdown closes at last event`() {
        val r = SessionBuilder.build(
            listOf(ev(SCREEN_ON, 0), ev(UNLOCK, 5), ev(APP_RESUMED, 10, "a"), ev(STARTUP, 10_000)),
            0, 20_000,
        )
        assertEquals(listOf(ScreenSession(0, 10, 5)), r.screenSessions)
        assertEquals(emptyList<AppSession>(), r.appSessions)
    }

    @Test
    fun `shutdown closes everything`() {
        val r = SessionBuilder.build(
            listOf(ev(SCREEN_ON, 0), ev(UNLOCK, 5), ev(APP_RESUMED, 10, "a"), ev(SHUTDOWN, 400)),
            0, 1000,
        )
        assertEquals(listOf(ScreenSession(0, 400, 5)), r.screenSessions)
        assertEquals(listOf(AppSession("a", 10, 400)), r.appSessions)
    }

    @Test
    fun `window starting mid session infers usage from foreground app`() {
        val r = SessionBuilder.build(
            listOf(ev(APP_RESUMED, 50, "a"), ev(APP_PAUSED, 300, "a"), ev(SCREEN_OFF, 310)),
            0, 1000,
        )
        assertEquals(listOf(ScreenSession(50, 310, 50)), r.screenSessions)
        assertEquals(listOf(AppSession("a", 50, 300)), r.appSessions)
    }

    @Test
    fun `unsorted input is handled`() {
        val r = SessionBuilder.build(
            listOf(ev(SCREEN_OFF, 300), ev(UNLOCK, 20), ev(SCREEN_ON, 10)),
            0, 1000,
        )
        assertEquals(listOf(ScreenSession(10, 300, 20)), r.screenSessions)
        assertEquals(10L, r.firstEventAt)
    }
}

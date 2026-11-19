package io.github.rudolfhk.screentime.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rudolfhk.screentime.core.EventType
import io.github.rudolfhk.screentime.core.RawEvent
import io.github.rudolfhk.screentime.data.AppDatabase
import io.github.rudolfhk.screentime.data.LogType
import io.github.rudolfhk.screentime.data.ScreenSessionEntity
import io.github.rudolfhk.screentime.data.SettingsStore
import io.github.rudolfhk.screentime.data.TrackingPeriodEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Runs the real sync pipeline against an in-memory database and a scripted event log. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrackingManagerTest {

    private val h = 3_600_000L
    private val d = 24 * h
    private var now = 1_800_000_000_000L
    private val events = ArrayList<RawEvent>()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var tracking: TrackingManager

    private val scheduler = object : SyncScheduling {
        var scheduled = false
        override fun schedule(intervalMinutes: Int) { scheduled = true }
        override fun cancel() { scheduled = false }
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(context)
        settings.resetForTest()
        tracking = TrackingManager(
            db = db,
            settings = settings,
            source = { from, to -> events.filter { it.timestamp in from..to } },
            appInfo = AppInfoResolver(context),
            scheduler = scheduler,
            hasUsageAccess = { true },
            clock = { now },
        )
    }

    @After
    fun tearDown() = db.close()

    /** One unlocked phone session with an app in it. */
    private fun use(at: Long, minutes: Int, pkg: String = "com.example.app") {
        events += RawEvent(EventType.SCREEN_ON, at)
        events += RawEvent(EventType.UNLOCK, at + 1_000)
        events += RawEvent(EventType.APP_RESUMED, at + 2_000, pkg)
        events += RawEvent(EventType.APP_PAUSED, at + minutes * 60_000L, pkg)
        events += RawEvent(EventType.SCREEN_OFF, at + minutes * 60_000L + 1_000)
    }

    @Test
    fun `backfilled start is fixed after the first sync`() = runBlocking {
        val oldest = now - 5 * d
        use(oldest, 10)
        use(now - 2 * h, 5)

        tracking.enable()
        val dao = db.usageDao()
        assertEquals(oldest, dao.firstTrackedAt().first())

        // Later syncs read only the newest part of the log; the period start must not follow.
        repeat(3) {
            now += h
            use(now - 30 * 60_000L, 3)
            tracking.sync()
        }
        assertEquals(oldest, dao.firstTrackedAt().first())
        assertEquals(oldest, dao.openTrackingPeriod()!!.start)
        assertEquals(5, dao.allScreenSessions().size)

        val log = dao.log(0).first().map { it.type }
        assertEquals(1, log.count { it == LogType.STARTED })
        assertEquals(1, log.count { it == LogType.BACKFILL })
        assertEquals(4, log.count { it == LogType.SYNC })
        assertTrue(scheduler.scheduled)

        now += h
        tracking.disable()
        assertNull(dao.openTrackingPeriod())
        assertEquals(oldest, dao.allTrackingPeriods().single().start)
        assertEquals(LogType.STOPPED, dao.log(0).first().first().type)
    }

    @Test
    fun `restart after a pause opens a second period without backfill`() = runBlocking {
        use(now - 3 * d, 10)
        tracking.enable()
        now += h
        tracking.disable()
        now += 2 * d
        use(now - h, 5) // happened while tracking was off: must not be recorded
        tracking.enable()
        val periods = db.usageDao().allTrackingPeriods()
        assertEquals(2, periods.size)
        assertEquals(now, periods.last().start)
        assertEquals(false, periods.last().backfilled)
        assertEquals(1, db.usageDao().allScreenSessions().size)
    }

    @Test
    fun `legacy damaged period is repaired once`() = runBlocking {
        val dao = db.usageDao()
        val earliest = now - 6 * d
        // State left by the old bug: sessions from six days ago, period start dragged to an hour ago.
        dao.upsertScreenSessions(listOf(ScreenSessionEntity(earliest, earliest + h, earliest + 1_000)))
        dao.upsertTrackingPeriod(TrackingPeriodEntity(now - h, null, backfilled = true))
        settings.startTracking(clipFrom = now - h, queryFrom = now - h)

        tracking.sync()
        assertEquals(earliest, dao.openTrackingPeriod()!!.start)
        assertEquals(earliest, settings.current().clipFrom)
        assertTrue(settings.current().periodRepairDone)
        assertEquals(1, dao.log(0).first().count { it.type == LogType.REPAIRED })
    }
}

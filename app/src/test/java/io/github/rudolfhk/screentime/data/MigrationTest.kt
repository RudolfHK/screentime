package io.github.rudolfhk.screentime.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Existing installs must upgrade without losing data. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun `v1 to v2 keeps data and adds the tracking log`() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO screen_sessions (start_ts, end_ts, unlock_ts) VALUES (1000, 5000, 1500)")
            execSQL("INSERT INTO tracking_periods (start_ts, end_ts, backfilled) VALUES (500, NULL, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true)
        db.query("SELECT COUNT(*) FROM screen_sessions").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM tracking_periods").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.execSQL("INSERT INTO tracking_log (ts, type, value, detail) VALUES (1, 'STARTED', 0, NULL)")
        db.query("SELECT COUNT(*) FROM tracking_log").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
    }
}

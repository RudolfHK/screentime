package io.github.rudolfhk.screentime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {

    @Test
    fun `round trip`() {
        val b = BackupBundle(
            exportedAt = 123,
            appVersion = "1.0",
            trackingPeriods = listOf(BackupTrackingPeriod(1, null, true)),
            dataGaps = listOf(BackupGap(5, 6)),
            screenSessions = listOf(BackupScreenSession(10, 20, 11), BackupScreenSession(30, 40, null)),
            unlocks = listOf(11),
            appSessions = listOf(BackupAppSession("a.b", 12, 19)),
            notifications = listOf(BackupNotification("a.b", 15)),
            apps = listOf(BackupApp("a.b", "A \"quoted\" app", 4)),
            settings = BackupSettings(90, listOf("x")),
        )
        assertEquals(b, BackupCodec.decode(BackupCodec.encode(b)))
    }

    @Test
    fun `rejects foreign and newer files`() {
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("{\"hello\":1}") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("not json") }
        val newer = BackupCodec.encode(BackupBundle(exportedAt = 0, schemaVersion = 99))
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(newer) }
    }

    @Test
    fun `drops corrupt rows and ignores unknown keys`() {
        val text = """
            {"format":"screentime-diagnostics-backup","schemaVersion":1,"exportedAt":1,"future":true,
             "screenSessions":[{"start":10,"end":5},{"start":1,"end":2}],
             "appSessions":[{"pkg":"","start":1,"end":2}]}
        """.trimIndent()
        val b = BackupCodec.decode(text)
        assertEquals(listOf(BackupScreenSession(1, 2, null)), b.screenSessions)
        assertEquals(0, b.appSessions.size)
    }

    @Test
    fun `csv escaping`() {
        assertEquals("a,\"b,c\",\"d\"\"e\"\r\n", Csv.row("a", "b,c", "d\"e"))
        assertEquals(",1\r\n", Csv.row(null, 1))
    }
}

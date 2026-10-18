package tech.miyukivigil.totem.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActivityTest {
    private fun code(at: Long, pc: String = "pc1") = ActivityEntry(at, pc, "Desk", ActivityKind.CODE, "GitHub")

    @Test
    fun codecRoundTrips() {
        val entries = listOf(
            code(100),
            ActivityEntry(101, "pc1", "Desk", ActivityKind.DENIED, "Bank", "user_denied"),
            ActivityEntry(102, "pc2", "Laptop", ActivityKind.LIST),
            ActivityEntry(103, "pc1", "Desk", ActivityKind.ALERT, null, RequestGuard.PAUSED),
        )
        assertEquals(entries, ActivityCodec.decode(ActivityCodec.encode(entries)))
    }

    @Test
    fun codecKeepsOnlyTheNewest() {
        val many = (1..ActivityCodec.MAX_ENTRIES + 20L).map { code(it) }
        val back = ActivityCodec.decode(ActivityCodec.encode(many))
        assertEquals(ActivityCodec.MAX_ENTRIES, back.size)
        assertEquals(many.last(), back.last())
    }

    @Test
    fun codecSkipsUnknownKinds() {
        val text = """[{"at":1,"pc":"a","pc_name":"A","kind":"from_the_future"},{"at":2,"pc":"a","pc_name":"A","kind":"list"}]"""
        assertEquals(listOf(ActivityEntry(2, "a", "A", ActivityKind.LIST)), ActivityCodec.decode(text))
    }

    @Test
    fun normalUseIsAllowed() {
        val log = (0 until RequestGuard.MAX_CODES - 1).map { code(1000L + it * 10) }
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(log, "pc1", 1100))
    }

    @Test
    fun aBurstPausesThatPcOnly() {
        val log = (0 until RequestGuard.MAX_CODES).map { code(1000L + it) }
        assertEquals(RequestGuard.Verdict.PauseNow, RequestGuard.check(log, "pc1", 1020))
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(log, "pc2", 1020))
    }

    @Test
    fun oldCodesDontCount() {
        val log = (0 until RequestGuard.MAX_CODES).map { code(1000L + it) }
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(log, "pc1", 1010 + RequestGuard.WINDOW_SECS))
    }

    @Test
    fun pauseLastsThenClears() {
        val burst = (0 until RequestGuard.MAX_CODES).map { code(1000L + it) }
        val log = burst + ActivityEntry(1020, "pc1", "Desk", ActivityKind.ALERT, null, RequestGuard.PAUSED)
        val paused = RequestGuard.check(log, "pc1", 1030)
        assertIs<RequestGuard.Verdict.Paused>(paused)
        assertEquals(1020 + RequestGuard.PAUSE_SECS, paused.until)
        // After the pause the earlier burst no longer counts, even if it's inside the window.
        assertEquals(RequestGuard.Verdict.Allow, RequestGuard.check(log, "pc1", 1020 + RequestGuard.PAUSE_SECS))
    }

    @Test
    fun dailyCheckIn() {
        val myt = 8 * 3600 // UTC+8
        // 2026-10-18 09:00 local = 01:00 UTC
        val morning = 1_792_285_200L
        val log = listOf(code(morning))
        assertTrue(DailyCheckIn.done(log, "pc1", morning + 12 * 3600, myt), "same day, 21:00")
        assertFalse(DailyCheckIn.done(log, "pc1", morning + 16 * 3600, myt), "next day, 01:00")
        assertFalse(DailyCheckIn.done(log, "pc2", morning + 60, myt), "other PC")
        assertFalse(DailyCheckIn.done(listOf(ActivityEntry(morning, "pc1", "Desk", ActivityKind.DENIED, "x")), "pc1", morning + 60, myt), "denied doesn't count")
    }

    @Test
    fun expiry() {
        val day = 86_400L
        assertFalse(PcExpiry.isExpired(0, 0, 0, 1000 * day), "never")
        assertFalse(PcExpiry.isExpired(0, 80 * day, 90, 100 * day), "used recently")
        assertTrue(PcExpiry.isExpired(0, 5 * day, 90, 100 * day), "unused for 95 days")
        assertFalse(PcExpiry.isExpired(50 * day, 0, 90, 100 * day), "paired recently, never used")
    }
}

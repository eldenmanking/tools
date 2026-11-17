package goals.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GateTest {
    private val ny = ZoneId.of("America/New_York")
    private val always = setOf("goals.app", "com.example.launcher", "com.android.settings")

    // 2026-11-17 08:00 in New York.
    private val morning = Instant.parse("2026-11-17T13:00:00Z")

    private fun status(blocked: Boolean, today: String = "2026-11-17", serverTime: String = "2026-11-17T12:42:00Z") =
        Status(
            today = today,
            todayScheduled = true,
            workout = "Upper",
            blocked = blocked,
            done = !blocked,
            allowlist = listOf("com.example.notes"),
            serverTime = serverTime,
            timeZone = "America/New_York",
        )

    @Test
    fun lockedTodayGatesAppsOutsideTheAllowlist() {
        val s = status(blocked = true)
        val state = gateState(s, morning, ny)
        assertIs<GateState.Locked>(state)
        assertTrue(shouldGate("com.example.video", state, s, always))
        assertFalse(shouldGate("com.example.notes", state, s, always))
        assertFalse(shouldGate("com.android.settings", state, s, always))
    }

    @Test
    fun doneOrRestDayGatesNothing() {
        val s = status(blocked = false)
        val state = gateState(s, morning, ny)
        assertEquals(GateState.Open, state)
        assertFalse(shouldGate("com.example.video", state, s, always))
    }

    @Test
    fun aStatusFromYesterdayIsUnknownAndGates() {
        val s = status(blocked = false, today = "2026-11-16", serverTime = "2026-11-16T22:00:00Z")
        val state = gateState(s, morning, ny)
        assertIs<GateState.Unknown>(state)
        assertTrue(shouldGate("com.example.video", state, s, always))
        assertFalse(shouldGate("com.example.notes", state, s, always))
    }

    @Test
    fun todayIsJudgedInTheSheetsTimeZone() {
        // 03:00 UTC on the 18th is still the 17th in New York.
        val late = Instant.parse("2026-11-18T03:00:00Z")
        assertEquals(GateState.Open, gateState(status(blocked = false), late, ZoneId.of("UTC")))
        // 05:30 UTC on the 18th is the 18th in New York: yesterday's "done" no longer counts.
        val next = Instant.parse("2026-11-18T05:30:00Z")
        assertIs<GateState.Unknown>(gateState(status(blocked = false), next, ZoneId.of("UTC")))
    }

    @Test
    fun windingTheClockBackDoesNotReuseAnOldOpenStatus() {
        val s = status(blocked = false, serverTime = "2026-11-17T20:00:00Z")
        // Device now claims 08:00, hours before the server answered.
        assertIs<GateState.Unknown>(gateState(s, morning, ny))
        // A few minutes of drift is fine.
        assertEquals(GateState.Open, gateState(s, Instant.parse("2026-11-17T19:57:00Z"), ny))
    }

    @Test
    fun neverCheckedGatesEverythingButAlwaysAllowed() {
        val state = gateState(null, morning, ny)
        assertIs<GateState.Unknown>(state)
        assertTrue(shouldGate("com.example.notes", state, null, always))
        assertFalse(shouldGate("goals.app", state, null, always))
    }
}

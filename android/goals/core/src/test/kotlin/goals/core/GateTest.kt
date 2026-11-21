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

class WindowGateTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(hm: String) = java.time.ZonedDateTime.parse("2026-11-17T$hm:00-05:00[America/New_York]").toInstant()
    private val upper = Session("Upper", "06:00", "10:00")
    private val stretch = Session("Stretch", "21:00", "23:00")
    private val s = Status(
        today = "2026-11-17", todayScheduled = true, workout = "Upper + Stretch", blocked = true,
        sessions = listOf(upper, stretch), serverTime = "2026-11-17T10:00:00Z", timeZone = "America/New_York",
    )

    @Test
    fun gatesOnlyInTheWindowOfAWorkoutNotDone() {
        assertEquals(GateState.Open, gateState(s, at("05:59"), ny))
        assertEquals(GateState.Locked(s, upper), gateState(s, at("06:00"), ny))
        assertEquals(GateState.Open, gateState(s, at("10:00"), ny))
        assertEquals(GateState.Locked(s, stretch), gateState(s, at("22:30"), ny))
        val done = s.copy(sessions = listOf(upper.copy(done = true), stretch))
        assertEquals(GateState.Open, gateState(done, at("07:00"), ny))
        assertEquals(GateState.Open, gateState(s.copy(skipped = true), at("07:00"), ny))
        assertEquals(s.nextSession(12 * 60), stretch)
    }

    @Test
    fun theAllowanceOpensForTwoMinutesOnceAnHour() {
        val start = at("07:00")
        val u = Unlocks(allowanceStart = start.toEpochMilli())
        assertEquals(GateState.Open, gateState(s, at("07:01"), ny, u))
        assertIs<GateState.Locked>(gateState(s, at("07:02"), ny, u))
        assertEquals(start.plus(ALLOWANCE_EVERY), allowanceReady(u, at("07:30")))
        assertEquals(null, allowanceReady(u, at("08:00")))
        assertEquals(null, allowanceReady(Unlocks(), at("08:00")))
        // It opens an unknown day too (offline), and winding the clock back doesn't restart it.
        assertEquals(GateState.Open, gateState(null, at("07:01"), ny, u))
        assertEquals(null, allowanceEnds(u, at("06:59")))
    }

    @Test
    fun anUnlockCodeOpensItsDayOnly() {
        val token = "t0k"
        // The same code as Core.js (tests/core.test.js computes it from the same token).
        assertEquals("279642", unlockCode(token, "2026-11-17"))
        val (r, u) = tryCode(Unlocks(), "279642", token, "2026-11-17", at("07:00"))
        assertEquals(CodeResult.Opened, r)
        assertEquals(GateState.Open, gateState(null, at("07:00"), ny, u))
        assertEquals(GateState.Open, gateState(s, at("22:00"), ny, u))
        val tomorrow = java.time.ZonedDateTime.parse("2026-11-18T07:00:00-05:00[America/New_York]").toInstant()
        assertIs<GateState.Unknown>(gateState(s, tomorrow, ny, u))
    }

    @Test
    fun wrongCodesWaitAfterFiveTries() {
        var u = Unlocks()
        repeat(4) { i ->
            val (r, next) = tryCode(u, "000000", "t0k", "2026-11-17", at("07:00"))
            assertEquals(CodeResult.Wrong(CODE_TRIES - 1 - i), r)
            u = next
        }
        val (r5, u5) = tryCode(u, "000000", "t0k", "2026-11-17", at("07:00"))
        assertIs<CodeResult.Wait>(r5)
        // Even the right code waits.
        assertIs<CodeResult.Wait>(tryCode(u5, "279642", "t0k", "2026-11-17", at("07:10")).first)
        assertEquals(CodeResult.Opened, tryCode(u5, "279642", "t0k", "2026-11-17", at("07:15")).first)
        assertEquals(UnlocksJson.parse(UnlocksJson.encode(u5)), u5)
    }
}

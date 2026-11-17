package goals.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundTest {
    private fun ev(t: Long, pkg: String, kind: AppEvent.Kind, cls: String = "$pkg.Main") = AppEvent(t, pkg, cls, kind)

    @Test
    fun theLatestResumedActivityIsInFront() {
        val events = listOf(
            ev(1, "com.example.launcher", AppEvent.Kind.RESUMED),
            ev(2, "com.example.launcher", AppEvent.Kind.PAUSED),
            ev(3, "com.example.video", AppEvent.Kind.RESUMED),
        )
        assertEquals("com.example.video", foregroundPackage(events))
        assertEquals("com.example.video", foregroundPackage(events.reversed())) // order doesn't matter
    }

    @Test
    fun aPausedActivityIsNotInFront() {
        val events = listOf(
            ev(1, "com.example.notes", AppEvent.Kind.RESUMED),
            ev(5, "com.example.video", AppEvent.Kind.RESUMED),
            ev(6, "com.example.video", AppEvent.Kind.PAUSED),
            ev(7, "com.example.video", AppEvent.Kind.OTHER),
        )
        assertEquals("com.example.notes", foregroundPackage(events))
    }

    @Test
    fun switchingActivitiesInsideOneAppKeepsIt() {
        val events = listOf(
            ev(1, "com.example.video", AppEvent.Kind.RESUMED, "List"),
            ev(2, "com.example.video", AppEvent.Kind.PAUSED, "List"),
            ev(3, "com.example.video", AppEvent.Kind.RESUMED, "Player"),
        )
        assertEquals("com.example.video", foregroundPackage(events))
    }

    @Test
    fun noEventsMeansUnknown() {
        assertNull(foregroundPackage(emptyList()))
    }
}

class RefreshTest {
    private val now = Instant.parse("2026-11-17T15:00:00Z")
    private val locked = GateState.Locked(Status(today = "2026-11-17", blocked = true))
    private fun ago(d: Duration): Instant = now.minus(d)

    @Test
    fun userChecksAreOnlyDebounced() {
        assertTrue(shouldFetch(RefreshTrigger.USER, GateState.Open, null, now, null, 0))
        assertFalse(shouldFetch(RefreshTrigger.USER, locked, null, now, ago(Duration.ofSeconds(3)), 0))
        assertTrue(shouldFetch(RefreshTrigger.USER, locked, null, now, ago(Duration.ofSeconds(11)), 0))
    }

    @Test
    fun aGatedAppFetchesWhileGatedAtMostOnceAMinute() {
        assertFalse(shouldFetch(RefreshTrigger.GATED_APP_OPENED, GateState.Open, null, now, null, 0))
        assertTrue(shouldFetch(RefreshTrigger.GATED_APP_OPENED, locked, null, now, ago(Duration.ofMinutes(2)), 0))
        assertFalse(shouldFetch(RefreshTrigger.GATED_APP_OPENED, locked, null, now, ago(Duration.ofSeconds(30)), 0))
        assertTrue(shouldFetch(RefreshTrigger.GATED_APP_OPENED, GateState.Unknown("x"), null, now, null, 0))
    }

    @Test
    fun backgroundFetchesWhenStaleOrUnknown() {
        val fresh = Status(today = "2026-11-17", serverTime = ago(Duration.ofHours(1)).toString())
        val stale = Status(today = "2026-11-17", serverTime = ago(Duration.ofHours(4)).toString())
        assertFalse(shouldFetch(RefreshTrigger.BACKGROUND, GateState.Open, fresh, now, ago(Duration.ofHours(1)), 0))
        assertTrue(shouldFetch(RefreshTrigger.BACKGROUND, GateState.Open, stale, now, ago(Duration.ofHours(4)), 0))
        // After midnight the cached status no longer vouches for today.
        assertTrue(shouldFetch(RefreshTrigger.BACKGROUND, GateState.Unknown("new day"), fresh, now, ago(Duration.ofHours(1)), 0))
    }

    @Test
    fun backgroundBacksOffAfterFailures() {
        assertEquals(Duration.ofHours(3), backoff(0))
        assertEquals(Duration.ofHours(6), backoff(1))
        assertEquals(Duration.ofHours(12), backoff(2))
        assertEquals(Duration.ofHours(12), backoff(50))
        val unknown = GateState.Unknown("offline")
        assertFalse(shouldFetch(RefreshTrigger.BACKGROUND, unknown, null, now, ago(Duration.ofHours(5)), 1))
        assertTrue(shouldFetch(RefreshTrigger.BACKGROUND, unknown, null, now, ago(Duration.ofHours(7)), 1))
    }
}

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

class ForegroundTrackerTest {
    private fun ev(t: Long, pkg: String, kind: AppEvent.Kind) = AppEvent(t, pkg, "$pkg.Main", kind)

    @Test
    fun keepsStateAcrossBatches() {
        val t = ForegroundTracker()
        assertNull(t.update(emptyList()))
        assertEquals("com.example.video", t.update(listOf(ev(1, "com.example.video", AppEvent.Kind.RESUMED))))
        // A later poll with no new events keeps the answer.
        assertEquals("com.example.video", t.update(emptyList()))
        // The app pauses and the launcher resumes in the next batch.
        val next = listOf(ev(5, "com.example.video", AppEvent.Kind.PAUSED), ev(6, "com.example.launcher", AppEvent.Kind.RESUMED))
        assertEquals("com.example.launcher", t.update(next))
        // The same events again (overlapping windows) change nothing.
        assertEquals("com.example.launcher", t.update(next))
    }

    @Test
    fun aPauseWithNothingResumedKeepsTheLastAnswer() {
        val t = ForegroundTracker()
        t.update(listOf(ev(1, "com.example.notes", AppEvent.Kind.RESUMED)))
        assertEquals("com.example.notes", t.update(listOf(ev(2, "com.example.notes", AppEvent.Kind.PAUSED))))
    }
}

class NoticeTest {
    private val status = Status(
        today = "2026-11-17", todayScheduled = true, workout = "Upper", blocked = true, streak = 12, tokens = 1,
        goalsProgress = listOf(
            GoalProgress("section", "Push", 2.0, 1, false),
            GoalProgress("section", "Pull", 2.0, 2, true),
            GoalProgress("section", "Core", 1.0, 0, false),
        ),
    )

    @Test
    fun saysWhatIsLockedAndTheStreak() {
        assertEquals(Notice("Locked · Upper · 1 of 3 goals", "Streak 12 · Tokens 1 of 2"), notice(GateState.Locked(status), status))
        assertEquals(Notice("Done for today · Upper", "Streak 12 · Tokens 1 of 2"), notice(GateState.Open, status.copy(done = true, open = true)))
        assertEquals("Open · Upper not done yet", notice(GateState.Open, status).title)
        assertEquals("Rest day", notice(GateState.Open, status.copy(todayScheduled = false)).title)
        assertEquals(Notice("Locked · today not checked yet", "Not checked yet."), notice(GateState.Unknown("Not checked yet."), null))
    }

    @Test
    fun saysWhichWindowLocksAndWhichComesNext() {
        val upper = Session("Upper", "06:00", "10:00", goalsProgress = status.goalsProgress)
        val stretch = Session("Stretch", "21:00", "23:00")
        val s = status.copy(sessions = listOf(upper, stretch))
        assertEquals("Locked · Upper until 10:00 · 1 of 3 goals", notice(GateState.Locked(s, upper), s).title)
        assertEquals("Open · Stretch gates at 21:00", notice(GateState.Open, s, minute = 12 * 60).title)
    }
}

package goals.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import goals.app.Need
import goals.core.DayEvent
import goals.core.GoalProgress
import goals.core.GoalRule
import goals.core.Goals
import goals.core.Workout
import goals.core.Status
import goals.core.WeekDay
import org.junit.Rule
import org.junit.Test

/**
 * Snapshots of the screens on an e-ink-sized portrait tablet (1404x1872 at ~226 ppi).
 * Record with ./gradlew :app:recordPaparazziDebug, check with :app:verifyPaparazziDebug.
 */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            // The tablet: 824x1648 px at 320 dpi (412x824 dp).
            screenWidth = 824,
            screenHeight = 1648,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            orientation = ScreenOrientation.PORTRAIT,
        ),
        // Draws the status bar, so the screens get the height they get on the tablet.
        showSystemUi = true,
    )

    private val week = listOf("Rest", "Lower", "Upper", "Skate", "Lower", "Upper", "Rest")
        .mapIndexed { i, w -> WeekDay("2026-11-${15 + i}", w, "default", locked = i <= 2) }

    private val locked = Status(
        today = "2026-11-17",
        todayScheduled = true,
        workout = "Upper",
        blocked = true,
        streak = 12,
        longest = 18,
        tokens = 1,
        goalsProgress = listOf(
            GoalProgress("section", "Push", 2.0, 1, false),
            GoalProgress("section", "Pull", 2.0, 2, true),
            GoalProgress("section", "Core", 1.0, 0, false),
        ),
        week = week,
        history = listOf(DayEvent("2026-11-16", "Lower", "done"), DayEvent("2026-11-17", "Upper", "pending")),
        serverTime = "2026-11-17T12:42:00Z",
        timeZone = "America/New_York",
    )

    @Test
    fun gateLocked() = paparazzi.snapshot {
        GateScreen("2026-11-17", locked, unknownReason = null, checking = false, error = null, onCheckAgain = {}, onDebugUnlock = null)
    }

    @Test
    fun gateManyGoals() = paparazzi.snapshot {
        val many = listOf("Push", "Pull", "Legs", "Core", "Cardio", "Stretch", "Free")
            .map { GoalProgress("section", it, 2.0, 1, false) }
        GateScreen("2026-11-17", locked.copy(goalsProgress = many), unknownReason = null, checking = false, error = null, onCheckAgain = {}, onDebugUnlock = null)
    }

    @Test
    fun gateUnknown() = paparazzi.snapshot {
        GateScreen(
            "2026-11-18",
            locked.copy(today = "2026-11-16"),
            unknownReason = "Last checked 2026-11-16.",
            checking = false,
            error = "no connection",
            onCheckAgain = {},
            onDebugUnlock = null,
        )
    }

    @Test
    fun homeDone() = paparazzi.snapshot {
        val done = locked.copy(
            blocked = false,
            done = true,
            streak = 13,
            history = listOf(DayEvent("2026-11-16", "Lower", "done"), DayEvent("2026-11-17", "Upper", "done")),
        )
        AppFrame(Tab.Status, {}) { HomeScreen(done) }
    }

    @Test
    fun homeLocked() = paparazzi.snapshot {
        AppFrame(Tab.Status, {}) { HomeScreen(locked, HomeCheck(false, null, {}, null)) }
    }

    @Test
    fun schedule() = paparazzi.snapshot {
        val next = locked.week.map { it.copy(date = java.time.LocalDate.parse(it.date).plusDays(7).toString(), locked = false) }
        AppFrame(Tab.Schedule, {}) { ScheduleScreen(locked, ScheduleMode.Week, {}, editor = null, weeks = listOf(locked.week, next), week = 1) }
    }

    private val savedDefault = listOf("Rest", "Upper", "Lower", "Rest", "Upper", "Lower", "Skate")

    private fun editor(draft: List<String>, notice: String? = null) = DefaultWeekUi(
        draft = draft, saved = savedDefault, workouts = listOf("Upper", "Lower", "Skate"), minGymDays = 4,
        loading = false, saving = false, errors = emptyList(), notice = notice, onRetry = {}, onPickDay = {}, onSave = {},
    )

    @Test
    fun defaultWeekSaved() = paparazzi.snapshot {
        AppFrame(Tab.Schedule, {}) { ScheduleScreen(locked, ScheduleMode.Default, {}, editor(savedDefault, "Saved. It starts Thursday, Nov 19.")) }
    }

    @Test
    fun defaultWeekTooFewDays() = paparazzi.snapshot {
        val draft = savedDefault.toMutableList().also { it[1] = "Rest"; it[2] = "Rest" }
        AppFrame(Tab.Schedule, {}) { ScheduleScreen(locked, ScheduleMode.Default, {}, editor(draft)) }
    }

    @Test
    fun dayPicker() = paparazzi.snapshot {
        AppFrame(Tab.Schedule, {}) { DayPicker(0, listOf("Upper", "Lower", "Skate"), "Upper + Skate", {}, {}) }
    }

    @Test
    fun workoutEditor() = paparazzi.snapshot {
        val w = Workout("Upper", listOf(GoalRule("section", "Push", 2.0), GoalRule("section", "Pull", 2.0), GoalRule("exercise", "Figure skating")))
        AppFrame(Tab.Goals, {}) { WorkoutEditor(w, {}, {}, {}, {}) }
    }

    @Test
    fun addGoal() = paparazzi.snapshot {
        AppFrame(Tab.Goals, {}) { AddGoal({}, {}) }
    }

    @Test
    fun goals() = paparazzi.snapshot {
        val goals = Goals(
            current = listOf(
                Workout("Upper", listOf(GoalRule("section", "Push", 2.0), GoalRule("section", "Pull", 2.0), GoalRule("section", "Core", 1.0))),
                Workout("Lower", listOf(GoalRule("section", "Legs", 2.0), GoalRule("section", "Core", 1.0))),
                Workout("Skate", listOf(GoalRule("exercise", "Figure skating"))),
            ),
        )
        AppFrame(Tab.Goals, {}) { GoalsScreen(goals, null, null) }
    }

    @Test
    fun allowlist() = paparazzi.snapshot {
        AppFrame(Tab.Allowlist, {}) { AllowlistScreen(
                locked,
                AllowlistUi(
                    apps = listOf(goals.app.data.AppEntry("Calendar", "com.example.calendar"), goals.app.data.AppEntry("Notes", "com.example.notes"),
                        goals.app.data.AppEntry("Reader", "com.example.reader")),
                    draft = listOf("com.example.notes", "com.example.reader"), saved = listOf("com.example.notes"), pending = null,
                    saving = false, errors = emptyList(), notice = null, onToggle = {}, onSave = {},
                ),
            ) }
    }

    @Test
    fun setup() = paparazzi.snapshot {
        SetupScreen(missing = listOf(Need.OVERLAY, Need.BATTERY), onOpen = {}, onSkip = null)
    }
}

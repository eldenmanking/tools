package goals.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import goals.app.Need
import goals.core.DayEvent
import goals.core.GoalProgress
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
        showSystemUi = false,
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
        HomeScreen(done, notice = null, onSchedule = {}, onAllowlist = {})
    }

    @Test
    fun homeLocked() = paparazzi.snapshot {
        HomeScreen(locked, notice = null, onSchedule = {}, onAllowlist = {}, check = HomeCheck(false, null, {}, null))
    }

    @Test
    fun setup() = paparazzi.snapshot {
        SetupScreen(missing = listOf(Need.OVERLAY, Need.BATTERY), onOpen = {}, onSkip = null)
    }
}

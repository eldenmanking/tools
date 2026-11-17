package goals.app.data

import goals.core.DayEvent
import goals.core.GoalProgress
import goals.core.Status
import goals.core.StatusResult
import goals.core.WeekDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Where statuses come from. Step 4 adds the Apps Script endpoint. */
interface StatusSource {
    suspend fun fetch(): StatusResult
}

/**
 * Step 2 stand-in for the endpoint: today is an Upper day (Push 1/2, Pull 2/2, Core 0/1),
 * matching the mocks, until [markDone] (the debug "I finished" button) meets every goal.
 */
object SampleStatusSource : StatusSource {
    @Volatile
    private var done = false

    fun markDone() {
        done = true
    }

    override suspend fun fetch(): StatusResult {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val sunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val plan = listOf("Rest", "Lower", "Upper", "Skate", "Lower", "Upper", "Rest")
        val week = plan.mapIndexed { i, w ->
            val d = sunday.plusDays(i.toLong())
            WeekDay(d.toString(), w, source = "default", locked = !d.isAfter(today))
        }
        val gymToday = plan[today.dayOfWeek.value % 7] != "Rest"
        val history = week.filter { it.workout != "Rest" && LocalDate.parse(it.date).isBefore(today) }
            .map { DayEvent(it.date, it.workout, "done") }
        val goals = listOf(
            GoalProgress("section", "Push", 2.0, if (done) 2 else 1, done),
            GoalProgress("section", "Pull", 2.0, 2, true),
            GoalProgress("section", "Core", 1.0, if (done) 1 else 0, done),
        )
        return StatusResult.Ok(
            Status(
                today = today.toString(),
                todayScheduled = gymToday,
                workout = if (gymToday) plan[today.dayOfWeek.value % 7] else "Rest",
                done = gymToday && done,
                blocked = gymToday && !done,
                streak = 12 + if (done) 1 else 0,
                longest = 18,
                tokens = 1,
                goalsProgress = if (gymToday) goals else emptyList(),
                allowlist = listOf("com.android.calendar"),
                week = week,
                history = history,
                serverTime = Instant.now().toString(),
                timeZone = zone.id,
            ),
        )
    }
}

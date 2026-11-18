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

/** Where statuses come from: the Apps Script endpoint ([HttpStatusSource]) or the sample. */
interface StatusSource {
    suspend fun fetch(): StatusResult
}

/**
 * Stand-in for the endpoint when local.properties doesn't name one: today is an Upper day (Push 1/2, Pull 2/2, Core 0/1),
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
        // Earlier gym days this week were done; today is done or pending, as the server reports it.
        val history = week.filter { it.workout != "Rest" && !LocalDate.parse(it.date).isAfter(today) }
            .map { DayEvent(it.date, it.workout, if (it.date == today.toString() && !done) "pending" else "done") }
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

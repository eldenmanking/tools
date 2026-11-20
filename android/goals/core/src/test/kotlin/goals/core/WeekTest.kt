package goals.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WeekTest {
    @Test
    fun marksEachDayFromTheWeekAndHistory() {
        val days = listOf("Rest", "Lower", "Upper", "Skate", "Lower", "Upper", "Rest")
        val week = days.mapIndexed { i, w -> WeekDay(date = "2026-11-${15 + i}", workout = w) }
        val status = Status(
            today = "2026-11-18",
            week = week,
            history = listOf(
                DayEvent("2026-11-16", "Lower", "done"),
                DayEvent("2026-11-17", "Upper", "frozen"),
                DayEvent("2026-11-18", "Skate", "pending"),
                DayEvent("2026-11-10", "Upper", "missed"), // last week: not in the strip
            ),
        )
        val cells = weekCells(status)
        assertEquals(
            listOf(DayMark.REST, DayMark.DONE, DayMark.FROZEN, DayMark.GYM, DayMark.GYM, DayMark.GYM, DayMark.REST),
            cells.map { it.mark },
        )
        assertEquals(listOf(false, false, false, true, false, false, false), cells.map { it.isToday })
    }

    @Test
    fun restIsCaseInsensitive() {
        assertEquals(false, isGymDay("rest"))
        assertEquals(false, isGymDay(""))
        assertEquals(true, isGymDay("Skate"))
    }
}

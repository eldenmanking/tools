package goals.core

/** How the home screen draws one day of the week strip. */
enum class DayMark {
    /** Gym day, goals met: a filled block. */
    DONE,
    /** Gym day missed with no token: an "×". */
    MISSED,
    /** Gym day missed, a freeze token kept the streak: an "F". */
    FROZEN,
    /** Gym day still to come (or today, not yet done): an empty box. */
    GYM,
    /** Rest day: a dash. */
    REST,
}

data class WeekCell(val date: String, val workout: String, val mark: DayMark, val isToday: Boolean)

fun isGymDay(workout: String?): Boolean = !workout.isNullOrBlank() && !workout.equals("rest", ignoreCase = true)

/** The current week (Sunday first) of [status], with each day's mark. */
fun weekCells(status: Status): List<WeekCell> {
    val events = status.history.associateBy { it.date }
    return status.week.map { day ->
        val mark = when (events[day.date]?.event) {
            "done" -> DayMark.DONE
            "missed" -> DayMark.MISSED
            "frozen" -> DayMark.FROZEN
            else -> if (isGymDay(day.workout)) DayMark.GYM else DayMark.REST
        }
        WeekCell(day.date, day.workout, mark, day.date == status.today)
    }
}

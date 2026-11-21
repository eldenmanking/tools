package goals.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * When a workout is gated: from [start] to [end], minutes since midnight in the sheet's time zone
 * (parseWindow in Core.js). Stored as "HH:MM-HH:MM".
 */
data class Window(val start: Int, val end: Int) {
    val allDay: Boolean get() = start == 0 && end == MINUTES_PER_DAY

    /** "06:00-10:00", as the server stores it. */
    fun format(): String = "${hhmm(start)}-${hhmm(end)}"

    operator fun contains(minute: Int): Boolean = minute in start until end

    companion object {
        val ALL_DAY = Window(0, MINUTES_PER_DAY)

        /** "06:00-10:00" (or "6:00-10:00") -> Window, or null if it isn't one. */
        fun parse(s: String?): Window? {
            val p = s?.split('-')?.takeIf { it.size == 2 } ?: return null
            val a = minuteOf(p[0]) ?: return null
            val b = minuteOf(p[1]) ?: return null
            return if (a < b) Window(a, b) else null
        }
    }
}

const val MINUTES_PER_DAY = 24 * 60

/** A workout can be planned, moved or dropped until this many minutes before its window (LOCK_MINUTES in Core.js). */
const val LOCK_MINUTES = 60

/** "6:30" or "06:30" -> 390; "24:00" is the end of the day. */
fun minuteOf(s: String): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (min > 59 || h > 24 || (h == 24 && min > 0)) return null
    return h * 60 + min
}

private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** 390 -> "6:30", for showing. */
fun clockText(m: Int): String = "${m / 60}:%02d".format(m % 60)

/** "6:00–10:00", or "all day". */
fun windowText(w: Window): String = if (w.allDay) "all day" else "${clockText(w.start)}–${clockText(w.end)}"

/**
 * One workout of a day's plan, with the day's own window if it sets one (planParts in Core.js);
 * [bad] when it tried to and the window didn't parse.
 */
data class PlanPart(val name: String, val window: Window? = null, val bad: Boolean = false) {
    /** "Upper" or "Upper@07:00-10:00". */
    fun format(): String = name + (window?.let { "@" + it.format() } ?: "")
}

/** A day's plan: "Upper@07:00-10:00 + Skate" -> [Upper at 7–10, Skate]; Rest has none. */
fun planParts(plan: String): List<PlanPart> =
    if (!isGymDay(plan)) emptyList()
    else plan.split('+').map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
        val at = p.indexOf('@')
        if (at < 0) {
            PlanPart(p)
        } else {
            val w = Window.parse(p.substring(at + 1).replace(" ", ""))
            PlanPart(p.substring(0, at).trim(), w, bad = w == null)
        }
    }

/** The workout names of a day's plan: "Upper@07:00-10:00 + Skate" -> [Upper, Skate]. */
fun workoutParts(name: String): List<String> = planParts(name).map { it.name }.filter { it.isNotEmpty() }

/** One day's plan from its [parts]: "Upper + Skate", or Rest when none. */
fun joinParts(parts: List<PlanPart>): String = parts.joinToString(" + ") { it.format() }.ifEmpty { REST }

/** A plan to show: "Upper, Skate". */
fun planNames(plan: String): String = workoutParts(plan).joinToString(", ").ifEmpty { REST }

/**
 * A plan to show with its windows: "Upper 6:00–10:00, Skate". [windowOf] gives a workout's own
 * window (null: all day); a part's window for the day comes first.
 */
fun planLabel(plan: String, windowOf: (String) -> Window?): String =
    planParts(plan).filter { it.name.isNotEmpty() }.joinToString(", ") { p ->
        val w = p.window ?: windowOf(p.name)
        if (w == null || w.allDay) p.name else "${p.name} ${windowText(w)}"
    }.ifEmpty { REST }

/** Minutes past midnight of [now] in [zone]. */
fun minuteOfDay(now: Instant, zone: ZoneId): Int = now.atZone(zone).let { it.hour * 60 + it.minute }

/**
 * Whether a workout whose window starts at [start] on [date] can no longer be changed at [now]
 * (in [zone]): it starts within [LOCK_MINUTES], or has started.
 */
fun windowLocked(date: LocalDate, start: Int, now: Instant, zone: ZoneId): Boolean {
    val here = now.atZone(zone)
    val days = ChronoUnit.DAYS.between(here.toLocalDate(), date)
    return days * MINUTES_PER_DAY + start - (here.hour * 60 + here.minute) <= LOCK_MINUTES
}

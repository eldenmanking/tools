package goals.app.ui

import goals.core.GoalProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** "2026-11-17" -> "Tuesday, Nov 17" (InkLabel upper-cases it). */
fun dayHeader(iso: String): String =
    try { LocalDate.parse(iso).format(DAY_HEADER) } catch (e: DateTimeParseException) { "" }

/** One-letter weekday for the week strip. */
fun weekdayLetter(iso: String): String =
    try { LocalDate.parse(iso).dayOfWeek.name.take(1) } catch (e: DateTimeParseException) { "?" }

/** When the server answered, in the sheet's time zone [zoneId] (else the device's), e.g. "7:42 AM". */
fun checkedAt(serverTime: String?, zoneId: String?): String? {
    val zone = try { zoneId?.let(ZoneId::of) } catch (e: java.time.DateTimeException) { null } ?: ZoneId.systemDefault()
    return try { serverTime?.let { Instant.parse(it).atZone(zone).format(CLOCK) } } catch (e: DateTimeParseException) { null }
}

/** "1 / 2" for a section goal, "done" or "not yet" for a single exercise. */
fun progressText(g: GoalProgress): String =
    if (g.type == "exercise") (if (g.met) "done" else "not yet")
    else "${g.logged} / ${if (g.min % 1.0 == 0.0) g.min.toInt().toString() else g.min.toString()}"

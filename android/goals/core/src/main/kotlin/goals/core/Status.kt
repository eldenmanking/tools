package goals.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Freeze tokens are capped at this many (see Core.js MAX_TOKENS). */
const val MAX_TOKENS = 2

/** One row of today's workout goals, e.g. "Push: 1 of 2 logged". */
@Serializable
data class GoalProgress(
    /** "section" (count exercises in a section) or "exercise" (one exercise logged). */
    val type: String = "section",
    val name: String,
    val min: Double,
    val logged: Int,
    val met: Boolean,
)

/** A day of the current week, Sunday first. */
@Serializable
data class WeekDay(
    val date: String,
    val workout: String,
    /** "saved", "default", "copied", or null when nothing is planned. */
    val source: String? = null,
    val locked: Boolean = false,
)

/** What happened on a past or current gym day. */
@Serializable
data class DayEvent(
    val date: String,
    val workout: String,
    /** "done", "pending" (today, not yet), "frozen" (missed, token spent), "skipped" (skipped with a token) or "missed". */
    val event: String,
)

/** One workout of today's plan with its window and goals (computeStatus sessions in Core.js). */
@Serializable
data class Session(
    val workout: String,
    /** "HH:MM" in the sheet's time zone. */
    val start: String = "00:00",
    /** "HH:MM"; "24:00" is midnight at the end of the day. */
    val end: String = "24:00",
    val done: Boolean = false,
    val goalsProgress: List<GoalProgress> = emptyList(),
) {
    val window: Window get() = Window(minuteOf(start) ?: 0, minuteOf(end) ?: MINUTES_PER_DAY)
}

@Serializable
data class PendingAllowlist(val from: String, val packages: List<String>)

/** The Apps Script status response (computeStatus in Core.js). */
@Serializable
data class Status(
    /** The server's date (yyyy-mm-dd) in [timeZone]. */
    val today: String,
    val todayScheduled: Boolean = false,
    val workout: String = "Rest",
    val done: Boolean = false,
    val unknownWorkout: Boolean = false,
    /** Today was skipped with a freeze token. */
    val skipped: Boolean = false,
    /** Gated when the server answered. The app judges the time itself from [sessions] (see [gatedSession]). */
    val blocked: Boolean = false,
    /** Nothing is left to gate today: a rest day, done, or skipped. */
    val open: Boolean = false,
    /** Today's workouts, each with its window, in plan order. Empty from servers before windows. */
    val sessions: List<Session> = emptyList(),
    /** Today can be skipped: it's blocked and a token is left to spend. */
    val canSkip: Boolean = false,
    val streak: Int = 0,
    val longest: Int = 0,
    /** Earned freeze tokens. */
    val tokens: Int = 0,
    /** Tokens granted for today only, spent first. */
    val todayTokens: Int = 0,
    val goalsProgress: List<GoalProgress> = emptyList(),
    val allowlist: List<String> = emptyList(),
    val pendingAllowlist: PendingAllowlist? = null,
    val week: List<WeekDay> = emptyList(),
    val history: List<DayEvent> = emptyList(),
    /** When the server answered (ISO instant). */
    val serverTime: String? = null,
    /** The spreadsheet's time zone (IANA id). */
    val timeZone: String? = null,
)

/** The workout gating apps at [minute] (minutes past midnight in the sheet's time zone), if any. */
fun Status.gatedSession(minute: Int): Session? =
    if (skipped || open) null else sessions.firstOrNull { !it.done && minute in it.window }

/** Whether apps are gated at [minute]: in the window of a workout not done yet. */
fun Status.gatedAt(minute: Int): Boolean = if (sessions.isEmpty()) blocked else gatedSession(minute) != null

/** The next workout not done yet whose window starts after [minute], if any. */
fun Status.nextSession(minute: Int): Session? =
    if (skipped || open) null else sessions.filter { !it.done && it.window.start > minute }.minByOrNull { it.window.start }

/** Either a status or the server's (or parser's) error message. */
sealed interface StatusResult {
    data class Ok(val status: Status) : StatusResult
    data class Error(val message: String) : StatusResult
}

object StatusJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private data class ErrorBody(val error: String? = null)

    fun parse(text: String): StatusResult =
        try {
            val err = json.decodeFromString(ErrorBody.serializer(), text).error
            if (err != null) StatusResult.Error(err)
            else StatusResult.Ok(json.decodeFromString(Status.serializer(), text))
        } catch (e: SerializationException) {
            StatusResult.Error("bad response: ${e.message}")
        } catch (e: IllegalArgumentException) {
            StatusResult.Error("bad response: ${e.message}")
        }

    fun encode(status: Status): String = json.encodeToString(Status.serializer(), status)
}

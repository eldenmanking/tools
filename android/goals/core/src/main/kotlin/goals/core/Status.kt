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
    /** "done", "pending" (today, not yet), "frozen" (missed, token spent) or "missed". */
    val event: String,
)

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
    /** True while other apps must be gated. */
    val blocked: Boolean = false,
    val streak: Int = 0,
    val longest: Int = 0,
    val tokens: Int = 0,
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

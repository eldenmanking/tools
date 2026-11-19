package goals.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The name the server uses for a day without a workout. */
const val REST = "Rest"

/** One goal of a workout: at least [min] exercises from a section, or one exercise logged. */
@Serializable
data class GoalRule(
    /** "section" or "exercise". */
    val type: String,
    val name: String,
    val min: Double = 1.0,
)

@Serializable
data class Workout(val name: String, val rules: List<GoalRule> = emptyList())

/** A default week, Sunday first, in effect from [from] (yyyy-mm-dd). */
@Serializable
data class DatedWeek(val from: String, val days: List<String>)

@Serializable
data class Defaults(val current: DatedWeek? = null, val pending: DatedWeek? = null)

@Serializable
data class PendingWorkouts(val from: String, val workouts: List<Workout>)

@Serializable
data class Goals(val current: List<Workout> = emptyList(), val pending: PendingWorkouts? = null)

/** One week of the plan as the server sees it (weekView in Core.js). */
@Serializable
data class WeekView(val weekStart: String, val stored: Boolean = false, val days: List<WeekDay> = emptyList())

/** The Apps Script schedule response (doGet action=schedule in Code.js). */
@Serializable
data class ScheduleView(
    val today: String,
    /** Workouts a day from tomorrow on can use. */
    val workouts: List<String> = emptyList(),
    val minGymDays: Int = 4,
    /** This week and next. */
    val weeks: List<WeekView> = emptyList(),
    @SerialName("default") val defaults: Defaults = Defaults(),
    val goals: Goals = Goals(),
    val serverTime: String? = null,
    val timeZone: String? = null,
)

sealed interface ScheduleResult {
    data class Ok(val view: ScheduleView) : ScheduleResult
    data class Error(val message: String) : ScheduleResult
}

/** The answer to a save: saved (from [effectiveFrom]), refused with the server's reasons, or failed. */
sealed interface SaveResult {
    data class Saved(val effectiveFrom: String?) : SaveResult
    data class Refused(val errors: List<String>) : SaveResult
    data class Error(val message: String) : SaveResult
}

object ScheduleJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private data class SaveBody(
        val ok: Boolean? = null,
        val errors: List<String> = emptyList(),
        val error: String? = null,
        val effectiveFrom: String? = null,
    )

    @Serializable
    private data class SaveDefault(val action: String, val days: List<String>)

    @Serializable
    private data class ErrorBody(val error: String? = null)

    fun parse(text: String): ScheduleResult =
        try {
            val err = json.decodeFromString(ErrorBody.serializer(), text).error
            if (err != null) ScheduleResult.Error(err)
            else ScheduleResult.Ok(json.decodeFromString(ScheduleView.serializer(), text))
        } catch (e: SerializationException) {
            ScheduleResult.Error("bad response: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ScheduleResult.Error("bad response: ${e.message}")
        }

    fun parseSave(text: String): SaveResult =
        try {
            val b = json.decodeFromString(SaveBody.serializer(), text)
            when {
                b.error != null -> SaveResult.Error(b.error)
                b.ok == true -> SaveResult.Saved(b.effectiveFrom)
                else -> SaveResult.Refused(b.errors.ifEmpty { listOf("refused") })
            }
        } catch (e: SerializationException) {
            SaveResult.Error("bad response: ${e.message}")
        } catch (e: IllegalArgumentException) {
            SaveResult.Error("bad response: ${e.message}")
        }

    /** The POST body for saving a default week (7 names, Sunday first). */
    fun saveDefault(days: List<String>): String = json.encodeToString(SaveDefault.serializer(), SaveDefault("saveDefault", days))
}

/**
 * The default week the editor starts from: the one saved to start later (saving again replaces
 * it), else the one in effect, else all rest.
 */
fun editableDefault(view: ScheduleView): List<String> =
    (view.defaults.pending ?: view.defaults.current)?.days?.takeIf { it.size == 7 } ?: List(7) { REST }

/**
 * Why [days] (7 names, Sunday first) can't be saved as the default week, the same checks the
 * server makes: enough gym days, and every gym day one of [workouts].
 */
/** The workouts of one day's plan: "Upper + Skate" -> [Upper, Skate] (workoutParts in Core.js). */
fun workoutParts(name: String): List<String> = name.split('+').map { it.trim() }.filter { it.isNotEmpty() }

/** One day's plan from the chosen [parts]: "Upper + Skate", or Rest when none. */
fun joinWorkouts(parts: List<String>): String = parts.joinToString(" + ").ifEmpty { REST }

fun defaultWeekErrors(days: List<String>, workouts: List<String>, minGymDays: Int): List<String> {
    val errors = mutableListOf<String>()
    if (days.size != 7) return listOf("a week has 7 days")
    days.filter { isGymDay(it) }.flatMap(::workoutParts)
        .filter { p -> workouts.none { it.equals(p, ignoreCase = true) } }.distinct()
        .forEach { errors.add("\"$it\" isn't one of your workouts") }
    val gym = days.count { isGymDay(it) }
    if (gym < minGymDays) errors.add("At least $minGymDays gym days needed; this has $gym.")
    return errors
}

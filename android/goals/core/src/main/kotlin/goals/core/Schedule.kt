package goals.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

@Serializable
data class AllowlistView(val current: List<String> = emptyList(), val pending: PendingAllowlist? = null)

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
    val allowlist: AllowlistView = AllowlistView(),
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
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Serializable
    private data class SaveBody(
        val ok: Boolean? = null,
        val errors: List<String> = emptyList(),
        val error: String? = null,
        val effectiveFrom: String? = null,
    )


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
    fun saveDefault(days: List<String>): String = body("saveDefault") { put("days", strings(days)) }

    /** The POST body for saving the week starting [weekStart] (a Sunday): 7 names, Sunday first. */
    fun saveWeek(weekStart: String, days: List<String>): String =
        body("saveSchedule") { put("weekStart", JsonPrimitive(weekStart)); put("days", strings(days)) }

    /** The POST body for skipping today with a freeze token. */
    fun skipToday(): String = body("skipToday") {}

    /** The POST body for saving the allowlist (from tomorrow). */
    fun saveAllowlist(packages: List<String>): String = body("saveAllowlist") { put("packages", strings(packages)) }

    /** The POST body for saving the goals (from tomorrow). */
    fun saveGoals(workouts: List<Workout>): String =
        body("saveGoals") { put("workouts", json.encodeToJsonElement(ListSerializer(Workout.serializer()), workouts)) }

    private fun strings(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

    private fun body(action: String, fill: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject { put("action", JsonPrimitive(action)); fill() }.toString()
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

/** The allowlist the picker starts from: the one saved to start later, else the one in effect. */
fun editableAllowlist(view: ScheduleView): List<String> = view.allowlist.pending?.packages ?: view.allowlist.current

/** The goals the editor starts from: the ones saved to start later, else the ones in effect. */
fun editableGoals(view: ScheduleView): List<Workout> = view.goals.pending?.workouts ?: view.goals.current

/** Why [workouts] can't be saved, the same checks the server makes (validateGoals in Core.js). */
fun goalsErrors(workouts: List<Workout>): List<String> {
    if (workouts.isEmpty()) return listOf("Add at least one workout.")
    val errors = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    workouts.forEach { w ->
        val name = w.name.trim()
        when {
            name.isEmpty() -> errors.add("Every workout needs a name.")
            !isGymDay(name) -> errors.add("\"$name\" is reserved for rest days.")
            '+' in name -> errors.add("\"$name\": names can't use \"+\".")
            !seen.add(name.lowercase()) -> errors.add("\"$name\" is listed twice.")
        }
        if (w.rules.isEmpty()) errors.add("${name.ifEmpty { "A workout" }} needs at least one goal.")
        w.rules.filter { it.name.isBlank() }.forEach { _ -> errors.add("${name.ifEmpty { "A workout" }} has a goal without a name.") }
    }
    return errors.distinct()
}

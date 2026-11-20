package goals.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScheduleTest {
    private val sample = """
        {"today":"2026-11-18","workouts":["Upper","Lower","Skate"],"minGymDays":4,
         "weeks":[{"weekStart":"2026-11-15","stored":false,"days":[{"date":"2026-11-15","workout":"Rest","source":null,"locked":true}]}],
         "default":{"current":{"from":"2026-11-01","days":["Rest","Upper","Lower","Rest","Upper","Lower","Skate"]},"pending":null},
         "goals":{"current":[{"name":"Skate","rules":[{"type":"exercise","name":"Figure skating","min":1}]}],"pending":null},
         "allowlist":{"current":[],"pending":null},"serverTime":"2026-11-18T14:14:16.647Z","timeZone":"America/New_York"}
    """.trimIndent()

    @Test
    fun parsesTheScheduleResponse() {
        val v = assertIs<ScheduleResult.Ok>(ScheduleJson.parse(sample)).view
        assertEquals(listOf("Upper", "Lower", "Skate"), v.workouts)
        assertEquals("2026-11-01", v.defaults.current?.from)
        assertEquals("Figure skating", v.goals.current.single().rules.single().name)
        assertEquals("unauthorized", assertIs<ScheduleResult.Error>(ScheduleJson.parse("""{"error":"unauthorized"}""")).message)
    }

    @Test
    fun parsesSaveAnswers() {
        assertEquals(SaveResult.Saved("2026-11-19"), ScheduleJson.parseSave("""{"ok":true,"effectiveFrom":"2026-11-19","default":{}}"""))
        assertEquals(SaveResult.Refused(listOf("a week needs at least 4 gym days, got 3")),
            ScheduleJson.parseSave("""{"ok":false,"errors":["a week needs at least 4 gym days, got 3"]}"""))
        assertIs<SaveResult.Error>(ScheduleJson.parseSave("""{"error":"unauthorized"}"""))
        assertIs<SaveResult.Error>(ScheduleJson.parseSave("<html>"))
        assertEquals("""{"action":"saveDefault","days":["Rest","Upper"]}""", ScheduleJson.saveDefault(listOf("Rest", "Upper")))
    }

    @Test
    fun editorStartsFromThePendingDefault() {
        val v = assertIs<ScheduleResult.Ok>(ScheduleJson.parse(sample)).view
        assertEquals(listOf("Rest", "Upper", "Lower", "Rest", "Upper", "Lower", "Skate"), editableDefault(v))
        val pending = DatedWeek("2026-11-19", List(7) { "Upper" })
        assertEquals(pending.days, editableDefault(v.copy(defaults = v.defaults.copy(pending = pending))))
        assertEquals(List(7) { REST }, editableDefault(v.copy(defaults = Defaults())))
    }

    @Test
    fun requestBodies() {
        assertEquals("""{"action":"saveSchedule","weekStart":"2026-11-22","days":["Rest","Upper"]}""", ScheduleJson.saveWeek("2026-11-22", listOf("Rest", "Upper")))
        assertEquals("""{"action":"saveAllowlist","packages":["com.a.b"]}""", ScheduleJson.saveAllowlist(listOf("com.a.b")))
        assertEquals(
            """{"action":"saveGoals","workouts":[{"name":"Skate","rules":[{"type":"exercise","name":"Figure skating","min":1.0}]}]}""",
            ScheduleJson.saveGoals(listOf(Workout("Skate", listOf(GoalRule("exercise", "Figure skating"))))),
        )
    }

    @Test
    fun goalChecks() {
        val ok = Workout("Upper", listOf(GoalRule("section", "Push", 2.0)))
        assertEquals(emptyList(), goalsErrors(listOf(ok)))
        assertEquals(listOf("\"upper\" is listed twice.", "\"Rest\" is reserved for rest days.", "Swim needs at least one goal."),
            goalsErrors(listOf(ok, ok.copy(name = "upper"), ok.copy(name = "Rest"), Workout("Swim"))))
    }

    @Test
    fun combinedWorkouts() {
        assertEquals(listOf("Upper", "Skate"), workoutParts(" Upper+ Skate "))
        assertEquals("Upper + Skate", joinParts(listOf(PlanPart("Upper"), PlanPart("Skate"))))
        assertEquals(REST, joinParts(emptyList()))
        val workouts = listOf("Upper", "Lower", "Skate")
        assertEquals(emptyList(), defaultWeekErrors(listOf("Upper + Skate", "Upper", "Lower", "Rest", "Upper", "Rest", "Rest"), workouts, 4))
        assertEquals(listOf("\"Yoga\" isn't one of your workouts"),
            defaultWeekErrors(listOf("Upper + Yoga", "Upper", "Lower", "Rest", "Upper", "Rest", "Rest"), workouts, 4))
    }

    @Test
    fun defaultWeekChecks() {
        val workouts = listOf("Upper", "Lower", "Skate")
        assertEquals(emptyList(), defaultWeekErrors(listOf("Rest", "Upper", "lower", "Rest", "Upper", "Lower", "Rest"), workouts, 4))
        assertEquals(listOf("At least 4 gym days needed; this has 3."),
            defaultWeekErrors(listOf("Rest", "Upper", "Rest", "Rest", "Upper", "Lower", "Rest"), workouts, 4))
        assertEquals(listOf("\"Yoga\" isn't one of your workouts"),
            defaultWeekErrors(listOf("Yoga", "Upper", "Lower", "Rest", "Upper", "Lower", "Yoga"), workouts, 4))
    }

    @Test
    fun windows() {
        assertEquals(Window(360, 600), Window.parse("6:00-10:00"))
        assertEquals("06:00-24:00", Window(360, MINUTES_PER_DAY).format())
        listOf("10:00-06:00", "06:00", "25:00-26:00", "x").forEach { assertEquals(null, Window.parse(it), it) }
        assertEquals("6:00–10:00", windowText(Window(360, 600)))
        assertEquals("all day", windowText(Window.ALL_DAY))
        val parts = planParts("Upper@07:00-10:00 + Skate")
        assertEquals(listOf(PlanPart("Upper", Window(420, 600)), PlanPart("Skate")), parts)
        assertEquals("Upper@07:00-10:00 + Skate", joinParts(parts))
        assertEquals(listOf("Upper", "Skate"), workoutParts("Upper@07:00-10:00 + Skate"))
        assertEquals(emptyList(), planParts(REST))
        assertEquals("Upper, Skate", planNames("Upper@07:00-10:00 + Skate"))
        val windows = mapOf("skate" to Window(18 * 60, 20 * 60))
        assertEquals("Upper 7:00–10:00, Skate 18:00–20:00, Lower", planLabel("Upper@07:00-10:00 + Skate + Lower") { windows[it.lowercase()] })
        assertEquals(listOf("\"Upper\" has a bad time window."),
            defaultWeekErrors(listOf("Upper@soon", "Upper", "Upper", "Upper", "Rest", "Rest", "Rest"), listOf("Upper"), 4))
        assertEquals(listOf("\"U@p\": names can't use \"@\".", "Up has a bad time window."),
            goalsErrors(listOf(Workout("U@p", listOf(GoalRule("section", "Push"))), Workout("Up", listOf(GoalRule("section", "Push")), "9-8"))))
    }

    @Test
    fun windowLocksAnHourAhead() {
        val ny = java.time.ZoneId.of("America/New_York")
        val at = { hm: String -> java.time.ZonedDateTime.parse("2026-11-17T$hm:00-05:00[America/New_York]").toInstant() }
        val today = java.time.LocalDate.parse("2026-11-17")
        assertEquals(false, windowLocked(today, 6 * 60, at("04:59"), ny))
        assertEquals(true, windowLocked(today, 6 * 60, at("05:00"), ny))
        assertEquals(true, windowLocked(today, 0, at("05:00"), ny))
        // Tomorrow's all-day workout locks at 23:00 today.
        assertEquals(false, windowLocked(today.plusDays(1), 0, at("22:59"), ny))
        assertEquals(true, windowLocked(today.plusDays(1), 0, at("23:00"), ny))
    }
}

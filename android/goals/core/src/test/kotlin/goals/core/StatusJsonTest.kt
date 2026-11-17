package goals.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StatusJsonTest {
    @Test
    fun parsesTheServersStatus() {
        // Shaped like doGet's output; unknown keys (here "extra") are ignored.
        val text = """
            {"today":"2026-11-17","todayScheduled":true,"workout":"Upper","done":false,
             "unknownWorkout":false,"blocked":true,"streak":12,"longest":18,"tokens":1,
             "goalsProgress":[{"type":"section","name":"Push","min":2,"logged":1,"met":false},
                              {"type":"exercise","name":"Figure skating","min":1,"logged":1,"met":true}],
             "allowlist":["com.example.notes"],"pendingAllowlist":{"from":"2026-11-18","packages":["com.example.cal"]},
             "week":[{"date":"2026-11-15","workout":"Rest","source":"default","locked":true}],
             "history":[{"date":"2026-11-16","workout":"Lower","event":"done"}],
             "serverTime":"2026-11-17T12:42:00.000Z","timeZone":"America/New_York","extra":1}
        """.trimIndent()
        val s = assertIs<StatusResult.Ok>(StatusJson.parse(text)).status
        assertEquals("Upper", s.workout)
        assertEquals(2.0, s.goalsProgress[0].min)
        assertEquals("exercise", s.goalsProgress[1].type)
        assertEquals(listOf("com.example.cal"), s.pendingAllowlist?.packages)
        assertEquals("default", s.week[0].source)
        assertEquals(s, (StatusJson.parse(StatusJson.encode(s)) as StatusResult.Ok).status)
    }

    @Test
    fun reportsServerErrors() {
        assertEquals(StatusResult.Error("unauthorized"), StatusJson.parse("""{"error":"unauthorized"}"""))
        assertIs<StatusResult.Error>(StatusJson.parse("<html>Sign in</html>"))
        assertIs<StatusResult.Error>(StatusJson.parse("""{"blocked":true}""")) // no "today"
    }
}

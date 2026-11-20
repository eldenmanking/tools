package goals.core

/** The persistent notification's two lines. Redrawn only when this changes (e-ink). */
data class Notice(val title: String, val text: String)

/** What the persistent notification says for [state] at [minute] (of the sheet's day), given the last status fetched. */
fun notice(state: GateState, cached: Status?, minute: Int = 0): Notice {
    val stats = cached?.let { "Streak ${it.streak} · Tokens ${it.tokens} of $MAX_TOKENS" } ?: "Open Gym Gate to check."
    return when (state) {
        is GateState.Locked -> {
            val goals = state.session?.goalsProgress ?: state.status.goalsProgress
            val what = state.session?.let { "${it.workout} until ${clockText(it.window.end)}" } ?: planNames(state.status.workout)
            Notice("Locked · $what · ${goals.count { it.met }} of ${goals.size} goals", stats)
        }
        is GateState.Unknown -> Notice("Locked · today not checked yet", state.reason)
        GateState.Open -> {
            val next = cached?.nextSession(minute)
            val pending = cached != null && cached.todayScheduled && !cached.open
            Notice(
                when {
                    next != null -> "Open · ${next.workout} gates at ${clockText(next.window.start)}"
                    pending -> "Open · ${planNames(cached!!.workout)} not done yet"
                    cached?.skipped == true -> "Skipped today"
                    cached?.todayScheduled == true -> "Done for today · ${planNames(cached.workout)}"
                    else -> "Rest day"
                },
                stats,
            )
        }
    }
}

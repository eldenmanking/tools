package goals.core

/** The persistent notification's two lines. Redrawn only when this changes (e-ink). */
data class Notice(val title: String, val text: String)

/** What the persistent notification says for [state], given the last status fetched. */
fun notice(state: GateState, cached: Status?): Notice {
    val stats = cached?.let { "Streak ${it.streak} · Tokens ${it.tokens} of $MAX_TOKENS" } ?: "Open Gym Gate to check."
    return when (state) {
        is GateState.Locked -> {
            val goals = state.status.goalsProgress
            Notice("Locked · ${state.status.workout} · ${goals.count { it.met }} of ${goals.size} goals", stats)
        }
        is GateState.Unknown -> Notice("Locked · today not checked yet", state.reason)
        GateState.Open -> Notice(
            if (cached?.todayScheduled == true) "Done for today · ${cached.workout}" else "Rest day",
            stats,
        )
    }
}

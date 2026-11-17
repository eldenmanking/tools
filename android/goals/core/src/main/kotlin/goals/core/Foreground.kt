package goals.core

/** The parts of an android.app.usage.UsageEvents.Event the foreground check needs. */
data class AppEvent(val timeMillis: Long, val packageName: String, val className: String, val kind: Kind) {
    enum class Kind {
        /** ACTIVITY_RESUMED (1): an activity came to the front. */
        RESUMED,
        /** ACTIVITY_PAUSED (2) or ACTIVITY_STOPPED (23): it left the front. */
        PAUSED,
        /** Anything else; ignored. */
        OTHER,
    }
}

/**
 * The package in front after [events] (any order): the most recently resumed activity
 * that hasn't been paused since. Null when nothing is resumed, e.g. the window held no
 * events; the caller then keeps its last answer.
 */
fun foregroundPackage(events: List<AppEvent>): String? {
    val resumed = HashMap<Pair<String, String>, Long>()
    for (e in events.sortedBy { it.timeMillis }) {
        val key = e.packageName to e.className
        when (e.kind) {
            AppEvent.Kind.RESUMED -> resumed[key] = e.timeMillis
            AppEvent.Kind.PAUSED -> resumed.remove(key)
            AppEvent.Kind.OTHER -> Unit
        }
    }
    return resumed.maxByOrNull { it.value }?.key?.first
}

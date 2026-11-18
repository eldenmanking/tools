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
 * Tracks the app in front across polls of the usage events. Feed it each new batch of
 * events with [update]; it keeps which activities are resumed between batches, so a
 * batch with nothing new keeps the last answer. Duplicate events (overlapping query
 * windows) are harmless.
 */
class ForegroundTracker {
    private val resumed = HashMap<Pair<String, String>, Long>()

    /** The package in front, or null before any activity was seen resumed. */
    var current: String? = null
        private set

    fun update(events: List<AppEvent>): String? {
        for (e in events.sortedBy { it.timeMillis }) {
            val key = e.packageName to e.className
            when (e.kind) {
                AppEvent.Kind.RESUMED -> resumed[key] = e.timeMillis
                AppEvent.Kind.PAUSED -> resumed.remove(key)
                AppEvent.Kind.OTHER -> Unit
            }
        }
        // Nothing resumed (e.g. between one activity pausing and the next resuming):
        // keep the last answer rather than report nothing.
        resumed.maxByOrNull { it.value }?.key?.first?.let { current = it }
        return current
    }
}

/**
 * The package in front after [events] (any order): the most recently resumed activity
 * that hasn't been paused since, or null when nothing is resumed.
 */
fun foregroundPackage(events: List<AppEvent>): String? = ForegroundTracker().update(events)

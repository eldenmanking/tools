package goals.core

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** Why the app is thinking about fetching a status. */
enum class RefreshTrigger {
    /** The user tapped "Check again" or opened the app. */
    USER,
    /** The foreground service saw a gated app come to the front. */
    GATED_APP_OPENED,
    /** The periodic background job (WorkManager). */
    BACKGROUND,
}

/** Minimum gap between fetches of each kind, and the background backoff cap. */
object RefreshTimes {
    val USER_MIN_GAP: Duration = Duration.ofSeconds(10)
    val GATED_APP_MIN_GAP: Duration = Duration.ofMinutes(1)
    val BACKGROUND_INTERVAL: Duration = Duration.ofHours(3)
    val BACKGROUND_MAX: Duration = Duration.ofHours(12)
}

/**
 * Whether to fetch now. Kept infrequent for battery and e-ink: a gated app opening only
 * fetches while gated (Locked or Unknown), at most once a minute; the background job
 * fetches when the last good status is [RefreshTimes.BACKGROUND_INTERVAL] old, or a new
 * day may have started, backing off by doubling after each failure up to
 * [RefreshTimes.BACKGROUND_MAX].
 *   [lastAttempt]: when the last fetch was tried, whatever its result
 *   [failures]: fetches failed in a row since the last success
 */
fun shouldFetch(
    trigger: RefreshTrigger,
    state: GateState,
    cached: Status?,
    now: Instant,
    lastAttempt: Instant?,
    failures: Int,
): Boolean {
    fun since(t: Instant?): Duration = if (t == null) Duration.ofDays(365) else Duration.between(t, now)
    return when (trigger) {
        RefreshTrigger.USER -> since(lastAttempt) >= RefreshTimes.USER_MIN_GAP
        RefreshTrigger.GATED_APP_OPENED ->
            state != GateState.Open && since(lastAttempt) >= RefreshTimes.GATED_APP_MIN_GAP
        RefreshTrigger.BACKGROUND -> {
            val wait = backoff(failures)
            if (failures > 0) since(lastAttempt) >= wait
            else state is GateState.Unknown || since(cached?.serverTime?.let(::instantOrNull)) >= wait
        }
    }
}

/** BACKGROUND_INTERVAL doubled per failure, capped at BACKGROUND_MAX. */
fun backoff(failures: Int): Duration {
    var d = RefreshTimes.BACKGROUND_INTERVAL
    repeat(failures.coerceIn(0, 8)) { d = d.multipliedBy(2) }
    return if (d > RefreshTimes.BACKGROUND_MAX) RefreshTimes.BACKGROUND_MAX else d
}

private fun instantOrNull(s: String): Instant? = try { Instant.parse(s) } catch (e: DateTimeParseException) { null }

package goals.core

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Whether apps are gated right now, judged from the last status the app fetched. */
sealed interface GateState {
    /** Rest day, or today's goals are met: nothing is gated. */
    data object Open : GateState

    /** A gym day with goals unmet: apps outside the allowlist are gated. */
    data class Locked(val status: Status) : GateState

    /**
     * The cached status can't vouch for today (never fetched, from an earlier day, or the
     * device clock is behind the last check). Gate until a fresh check says otherwise, so
     * going offline or winding the clock back doesn't unlock anything.
     */
    data class Unknown(val reason: String) : GateState
}

/**
 * Judge [cached] (the last status fetched, or null) at [now]. "Today" is computed in the
 * sheet's time zone from the status, falling back to [deviceZone].
 */
fun gateState(cached: Status?, now: Instant, deviceZone: ZoneId): GateState {
    if (cached == null) return GateState.Unknown("Not checked yet.")
    val zone = cached.timeZone?.let { zoneOrNull(it) } ?: deviceZone
    val checkedAt = cached.serverTime?.let { instantOrNull(it) }
    if (checkedAt != null && now.isBefore(checkedAt.minusSeconds(CLOCK_SLACK_SECONDS))) {
        return GateState.Unknown("The clock is behind the last check.")
    }
    // now.atZone(zone).toLocalDate(), not LocalDate.ofInstant: that needs API 34, the tablet is 33.
    if (now.atZone(zone).toLocalDate().toString() != cached.today) {
        return GateState.Unknown("Last checked ${cached.today}.")
    }
    return if (cached.blocked) GateState.Locked(cached) else GateState.Open
}

/**
 * Whether opening [packageName] should show the gate. [alwaysAllowed] (this app, the
 * launcher, Settings) never is. While locked or unknown, only the allowlist of the last
 * status fetched is let through. When that status is from an earlier day the list may be
 * out of date until the next successful check.
 */
fun shouldGate(packageName: String, state: GateState, cached: Status?, alwaysAllowed: Set<String>): Boolean =
    when {
        state is GateState.Open -> false
        packageName in alwaysAllowed -> false
        else -> packageName !in (cached?.allowlist ?: emptyList())
    }

/** Device clock may run this far behind the server's before a cached status stops counting. */
private const val CLOCK_SLACK_SECONDS = 5 * 60L

private fun zoneOrNull(id: String): ZoneId? = try { ZoneId.of(id) } catch (e: DateTimeException) { null }

private fun instantOrNull(s: String): Instant? = try { Instant.parse(s) } catch (e: DateTimeParseException) { null }
